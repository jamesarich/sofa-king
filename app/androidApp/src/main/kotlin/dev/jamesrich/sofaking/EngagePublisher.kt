package dev.jamesrich.sofaking

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.engage.common.datamodel.AccountProfile
import com.google.android.engage.common.datamodel.ContinuationCluster
import com.google.android.engage.common.datamodel.Entity
import com.google.android.engage.common.datamodel.Image
import com.google.android.engage.common.datamodel.PlatformSpecificUri
import com.google.android.engage.common.datamodel.PlatformType
import com.google.android.engage.service.AppEngagePublishClient
import com.google.android.engage.service.PublishContinuationClusterRequest
import com.google.android.engage.video.datamodel.MovieEntity
import com.google.android.engage.video.datamodel.TvEpisodeEntity
import com.google.android.engage.video.datamodel.WatchNextType
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.itemLink
import dev.jamesrich.sofaking.data.runCatchingCancellable
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Publishes the library's Continue watching shelf through the Engage SDK, which Google TV reads in place of the Watch
 * Next API from the second half of 2027. The Engage service is part of the Play Store and takes rows only from apps
 * Google has enrolled; where [available] says it does not, [WatchNextPublisher] writes the Watch Next row itself. On an
 * Android TV launcher that reads Watch Next, the SDK writes the row there too, in place of the app's own entries.
 *
 * Calls block, so they run on the Watch Next writer's thread.
 */
internal class EngagePublisher(private val context: Context) {
    private val client = AppEngagePublishClient(context)
    private var wasAvailable: Boolean? = null

    /**
     * The household on this device as the service knows it: a random id kept for the install, since the app has no
     * accounts. Google TV refuses a Continue watching row without one. The row stays on this device: nothing syncs to
     * the viewer's Google account.
     */
    private val account by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(ACCOUNT, null)
            ?: UUID.randomUUID().toString().also { prefs.edit { putString(ACCOUNT, it) } }
        AccountProfile.Builder().setAccountId(id).build()
    }

    /** Hands [items] to the Engage service, or removes the row when there are none; false when it refused them. */
    fun publish(items: List<MediaItem>, serverUrl: String?): Boolean = runCatchingCancellable {
        WatchNextArtwork.grant(context)
        val now = System.currentTimeMillis()
        val entities = items.mapIndexedNotNull { index, item ->
            entity(item, serverUrl, engagedAt = now - index * ENGAGEMENT_STEP_MS)
        }.take(MAX_ENTITIES)
        if (entities.isEmpty()) {
            await(client.deleteContinuationCluster(account))
        } else {
            val cluster = ContinuationCluster.Builder().setAccountProfile(account).setSyncAcrossDevices(false)
            entities.forEach(cluster::addEntity)
            val request = PublishContinuationClusterRequest.Builder().setContinuationCluster(cluster.build())
            await(client.publishContinuationCluster(request.build()))
        }
        WatchNextArtwork.keepOnly(context, items.take(MAX_ENTITIES).mapNotNull { image(it, serverUrl)?.imageUri })
    }.onFailure {
        Log.w(TAG, "engage: Continue watching was not published: $it")
    }.isSuccess

    /**
     * Whether the Engage service takes this app's rows; false where there is no Play Store. The per-cluster form of
     * the check answers false for Continue watching even where the service takes the row (seen with Google's Engage
     * verification app, 2026-10), so this asks the plain one; a publish the service refuses falls back to Watch Next.
     */
    fun available(): Boolean = runCatchingCancellable {
        // Deprecated for the per-cluster form, which wrongly answers false; see above.
        @Suppress("DEPRECATION")
        await(client.isServiceAvailable())
    }.getOrDefault(false).also { available ->
        if (available != wasAvailable) {
            Log.i(TAG, if (available) "engage: publishing Continue watching" else "engage: writing Watch Next instead")
        }
        wasAvailable = available
    }

    /**
     * [item]'s card: an episode of its show, or a movie, for anything else the shelf plays. Null for an item without
     * the duration or artwork the service requires, which it would refuse the whole row for.
     */
    private fun entity(item: MediaItem, serverUrl: String?, engagedAt: Long): Entity? {
        val durationMs = item.durationMs?.takeIf { it > 0 } ?: return null
        val image = image(item, serverUrl) ?: return null
        val positionMs = item.progress?.takeIf { it.inProgress }?.positionMs ?: 0
        val type = if (positionMs > 0) WatchNextType.TYPE_CONTINUE else WatchNextType.TYPE_NEXT
        val play = PlatformSpecificUri.Builder()
            .setPlatformType(PlatformType.TYPE_ANDROID_TV)
            .setActionUri(itemLink(item.id).toUri())
            .build()
        val show = item.showTitle?.takeIf { item.kind == ItemKind.EPISODE }
        val season = item.season
        val episode = item.episode
        // Google TV needs an episode's show, season and number; an episode without them shows as a movie would.
        return if (show != null && season != null && episode != null) {
            TvEpisodeEntity.Builder()
                .setEntityId(item.id)
                .setName(item.title)
                .setShowTitle(show)
                .setSeasonNumber(season.toString())
                .setEpisodeNumber(episode)
                .setWatchNextType(type)
                .setLastEngagementTimeMillis(engagedAt)
                .apply { if (positionMs > 0) setLastPlayBackPositionTimeMillis(positionMs) }
                .setDurationMillis(durationMs)
                .addPlatformSpecificPlaybackUri(play)
                .setPlayBackUri(play.actionUri)
                .addPosterImage(image)
                .build()
        } else {
            MovieEntity.Builder()
                .setEntityId(item.id)
                .setName(item.title)
                .setWatchNextType(type)
                .setLastEngagementTimeMillis(engagedAt)
                .apply { if (positionMs > 0) setLastPlayBackPositionTimeMillis(positionMs) }
                .setDurationMillis(durationMs)
                .addPlatformSpecificPlaybackUri(play)
                .setPlayBackUri(play.actionUri)
                .addPosterImage(image)
                .apply { item.overview?.let(::setDescription) }
                .build()
        }
    }

    /**
     * The backdrop, the 16:9 picture the row's cards show, else the poster; both through the app's own artwork URIs,
     * as for Watch Next. The sizes are the shape the service lays the card out by, not the file's own.
     */
    private fun image(item: MediaItem, serverUrl: String?): Image? {
        val backdrop = item.backdrop?.let { WatchNextArtwork.contentUri(context, serverUrl, it) }
        val poster = item.poster?.let { WatchNextArtwork.contentUri(context, serverUrl, it) }
        return when {
            backdrop != null -> image(backdrop, BACKDROP_WIDTH, BACKDROP_HEIGHT)
            poster != null -> image(poster, POSTER_WIDTH, POSTER_HEIGHT)
            else -> null
        }
    }

    private fun image(uri: Uri, width: Int, height: Int): Image =
        Image.Builder().setImageUri(uri).setImageWidthInPixel(width).setImageHeightInPixel(height).build()

    private fun <T> await(task: Task<T>): T = Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS)
}

private const val TAG = "SofaKing"
private const val PREFS = "watch_next"
private const val ACCOUNT = "engage_account"

/** Google TV shows at most this many of one app's cards. */
private const val MAX_ENTITIES = 10

/** Cards made in one write sit this far apart in time, so the row keeps the shelf's order. */
private const val ENGAGEMENT_STEP_MS = 1_000L

private const val TIMEOUT_S = 10L
private const val BACKDROP_WIDTH = 1280
private const val BACKDROP_HEIGHT = 720
private const val POSTER_WIDTH = 480
private const val POSTER_HEIGHT = 720

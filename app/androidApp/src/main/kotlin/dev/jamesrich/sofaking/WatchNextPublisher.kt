package dev.jamesrich.sofaking

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.WatchNext
import dev.jamesrich.sofaking.data.itemLink
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the Android TV home screen's Watch Next row in step with the library's Continue watching shelf: one entry
 * per item, keyed by the item's id, that plays it through a `sofaking://vod` link. An entry the viewer removed from
 * the row stays removed while its item is on the shelf. Writes run one at a time on their own thread, and a shelf
 * that arrives while one is written replaces any still waiting.
 *
 * Lint reads the program classes' getters and builder setters as restricted, because tvprovider declares them on
 * restricted base classes; they are the library's public API on `WatchNextProgram`, as its own samples call them.
 */
@SuppressLint("RestrictedApi")
internal class WatchNextPublisher(private val context: Context) : WatchNext {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "watch-next") }
    private val waiting = AtomicReference<List<MediaItem>?>(null)

    override fun publish(items: List<MediaItem>) {
        if (waiting.getAndSet(items) == null) writer.execute { waiting.getAndSet(null)?.let(::write) }
    }

    private fun write(items: List<MediaItem>) {
        try {
            val resolver = context.contentResolver
            // The provider answers with this app's own entries only.
            val existing = resolver.query(
                WatchNextPrograms.CONTENT_URI,
                WatchNextProgram.PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(WatchNextProgram.fromCursor(cursor)) }
            }.orEmpty()
            val byItem = existing.associateBy { it.internalProviderId }
            val shown = items.map { it.id }.toSet()
            existing.filter {
                it.internalProviderId !in shown
            }.forEach { resolver.delete(TvContractCompat.buildWatchNextProgramUri(it.id), null, null) }
            WatchNextArtwork.grant(context)
            val now = System.currentTimeMillis()
            items.forEachIndexed { index, item ->
                val old = byItem[item.id]
                if (old != null && !old.isBrowsable) return@forEachIndexed
                val program = program(item, old, engagedAt = now - index * ENGAGEMENT_STEP_MS)
                if (old == null) {
                    resolver.insert(WatchNextPrograms.CONTENT_URI, program.toContentValues())
                } else {
                    resolver.update(
                        TvContractCompat.buildWatchNextProgramUri(old.id),
                        program.toContentValues(),
                        null,
                        null,
                    )
                }
            }
            WatchNextArtwork.keepOnly(context, items.mapNotNull { artwork(it)?.first })
        } catch (e: RuntimeException) {
            // A launcher without the TV provider, or one that refuses this app's writes, leaves the row as it was.
            Log.w(TAG, "watch next: the row was not updated: $e")
        }
    }

    /**
     * [item]'s entry. The launcher orders the row by the last engagement, so an entry keeps its own until its place
     * in the item changes; a new or moved one takes [engagedAt], which keeps the shelf's order.
     */
    private fun program(item: MediaItem, old: WatchNextProgram?, engagedAt: Long): WatchNextProgram {
        val positionMs = item.progress?.takeIf { it.inProgress }?.positionMs ?: 0
        val type = if (positionMs > 0) {
            WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
        } else {
            WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
        }
        val unchanged =
            old != null && old.watchNextType == type && old.lastPlaybackPositionMillis.toLong() == positionMs
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(type)
            .setLastEngagementTimeUtcMillis(if (unchanged) old.lastEngagementTimeUtcMillis else engagedAt)
            .setLastPlaybackPositionMillis(positionMs.toInt())
            .setInternalProviderId(item.id)
            .setIntent(play(item))
            .setDescription(item.overview)
        item.durationMs?.let { builder.setDurationMillis(it.toInt()) }
        if (item.kind == ItemKind.EPISODE && item.showTitle != null) {
            builder.setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                .setTitle(item.showTitle)
                .setEpisodeTitle(item.title)
            item.season?.let { builder.setSeasonNumber(it) }
            item.episode?.let { builder.setEpisodeNumber(it) }
        } else {
            builder.setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE).setTitle(item.title)
        }
        artwork(item)?.let { (uri, ratio) -> builder.setPosterArtUri(uri).setPosterArtAspectRatio(ratio) }
        return builder.build()
    }

    /**
     * The link that plays [item]. A running app takes it in the activity it has, through `onNewIntent`, rather than
     * stacking a second activity with a second player on its task.
     */
    private fun play(item: MediaItem): Intent = Intent(Intent.ACTION_VIEW, itemLink(item.id).toUri())
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The backdrop, which fits the row's wide cards, else the poster, as the launcher may read them. */
    private fun artwork(item: MediaItem): Pair<Uri, Int>? =
        item.backdrop?.let { WatchNextArtwork.contentUri(context, it) }?.let {
            it to
                TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
        }
            ?: item.poster?.let { WatchNextArtwork.contentUri(context, it) }?.let {
                it to
                    TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER
            }
}

private const val TAG = "SofaKing"

/** Entries made in one write sit this far apart in time, so the launcher keeps the shelf's order. */
private const val ENGAGEMENT_STEP_MS = 1_000L

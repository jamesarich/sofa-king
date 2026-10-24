package dev.jamesrich.sofaking

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Posters and backdrops for the Watch Next row. The launcher draws them in its own process, where a server on the
 * local network over plain HTTP may be refused, so each entry names a `content://` URI that this app answers instead:
 * the server's `/artwork/{item}/{role}` path under this app's authority. The launcher may read every URI under it,
 * a grant that lasts until the device restarts, so [ArtworkGrantReceiver] grants it again after a restart or an update.
 */
internal object WatchNextArtwork {
    private const val PREFS = "watch_next"
    private const val SERVER = "server"
    private val ROLES = setOf("poster", "backdrop")
    private val ID = Regex("[A-Za-z0-9._~-]+")

    fun authority(context: Context) = context.packageName + ".artwork"

    /**
     * The URI the launcher reads for [artworkUrl], an `/artwork/` URL the server gave; null for any other URL. The
     * server it names is remembered, since the launcher may ask for the artwork long after the app has closed.
     */
    fun contentUri(context: Context, artworkUrl: String): Uri? {
        val source = artworkUrl.toUri()
        if ((source.scheme != "http" && source.scheme != "https") || source.encodedAuthority == null ||
            !valid(source)
        ) {
            return null
        }
        val server = "${source.scheme}://${source.encodedAuthority}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(SERVER, null) != server) prefs.edit { putString(SERVER, server) }
        return Uri.Builder().scheme(
            "content",
        ).authority(authority(context)).encodedPath(source.encodedPath).encodedQuery(source.encodedQuery).build()
    }

    /** Lets every home screen app read the artwork URIs, which only this app's provider answers. */
    fun grant(context: Context) {
        val root = Uri.Builder().scheme("content").authority(authority(context)).path("artwork/").build()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL).map {
            it.activityInfo.packageName
        }.distinct().forEach { launcher ->
            context.grantUriPermission(
                launcher,
                root,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
    }

    /** Drops the cached artwork of entries no longer in the row. */
    fun keepOnly(context: Context, uris: List<Uri>) {
        val keep = uris.map(::cacheName).toSet()
        cacheDir(context).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    /**
     * [uri]'s image, fetched from the remembered server the first time it is asked for and kept in the cache after.
     * Only `/artwork/{item}/{poster|backdrop}` is answered, so the provider reaches nothing else on the server. One
     * fetch runs at a time, so two reads of one image never write its file together.
     */
    @Synchronized
    fun open(context: Context, uri: Uri): File {
        if (uri.authority != authority(context) || !valid(uri)) throw FileNotFoundException(uri.toString())
        val file = File(cacheDir(context), cacheName(uri))
        if (file.exists()) return file
        val server =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SERVER, null)
                ?: throw FileNotFoundException(uri.toString())
        val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
        val connection = URL(server + uri.encodedPath + query).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw FileNotFoundException("$uri: HTTP ${connection.responseCode}")
            }
            val partial = File(file.parentFile, file.name + ".part")
            connection.inputStream.use { input -> partial.outputStream().use(input::copyTo) }
            if (!partial.renameTo(file)) throw IOException("$uri: not cached")
            return file
        } finally {
            connection.disconnect()
        }
    }

    private fun valid(uri: Uri): Boolean {
        val segments = uri.pathSegments
        return segments.size == 3 && segments[0] == "artwork" && ID.matches(segments[1]) && segments[2] in ROLES
    }

    private fun cacheDir(context: Context) = File(context.cacheDir, "watch-next").apply { mkdirs() }

    private fun cacheName(uri: Uri): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    private const val TIMEOUT_MS = 10_000
}

/** Answers the launcher's reads of the Watch Next row's artwork; not exported, so only granted readers reach it. */
class WatchNextArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val context = checkNotNull(context)
        val file = try {
            WatchNextArtwork.open(context, uri)
        } catch (e: IOException) {
            Log.w("SofaKing", "watch next: artwork failed: $e")
            throw FileNotFoundException(uri.toString())
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** Grants the launcher the artwork again after a restart, which drops the grant, or an update of the app. */
class ArtworkGrantReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WatchNextArtwork.grant(context)
        }
    }
}

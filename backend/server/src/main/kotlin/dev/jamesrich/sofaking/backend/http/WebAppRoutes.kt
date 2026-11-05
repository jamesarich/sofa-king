package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.ServerConfig
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** Where the server serves the Sofa King web app: under a path of its own, so its service worker's scope ends there. */
internal const val WEB_APP_PATH = "/app"

/** Where the server serves the new web client (`SOFA_WEB2_DIR`), beside the app at [WEB_APP_PATH]. */
internal const val WEB2_APP_PATH = "/app2"

/** Whether [path], a routed path, is one of the web apps' files. */
internal fun isWebAppPath(path: String): Boolean =
    path.startsWith("$WEB_APP_PATH/") || path.startsWith("$WEB2_APP_PATH/")

/**
 * The web apps' policy. WebAssembly needs `'wasm-unsafe-eval'`, and Compose adds a style element to the page it draws
 * in; images and fetches may reach HTTPS hosts, where a guide's program images live; the players read streams through
 * media source buffers; Coil decodes artwork in a worker it starts from a `blob:` address, and without it every poster
 * stays a placeholder. The new client's bundle is `'self'`, and hls.js starts its worker from a `blob:` address and
 * plays through a `blob:` media source, all of which this allows; its caption tracks are `<track>` elements whose
 * source is the empty `data:,WEBVTT`, which `media-src` must allow too.
 */
internal const val WEB_APP_CSP =
    "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob: https:; media-src 'self' blob: data:; connect-src 'self' https:; " +
        "worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

/**
 * Whether the server serves the web app: [ServerConfig.webDirectory] holds its page. A folder without it, as a local
 * image built with an empty `web/` has, serves none.
 */
internal fun ServerConfig.servesWebApp(): Boolean = webDirectory?.resolve("index.html")?.isRegularFile() == true

/** Whether the server serves the new web client: [ServerConfig.web2Directory] holds its page. */
internal fun ServerConfig.servesWeb2App(): Boolean = web2Directory?.resolve("index.html")?.isRegularFile() == true

/**
 * The web app's address that pairs it with [code] as it opens. The code rides in the fragment, which no request
 * carries, so it reaches no log or `Referer`.
 */
internal fun webAppPairingPath(code: String): String = "$WEB_APP_PATH/#pair=$code"

/**
 * A bundle file named by its content's hash, as webpack names the `.wasm` files and esbuild the new client's `.js` and
 * `.css` (`app-3FJ2K9QX.js`), which never changes in place.
 */
private val HASHED_NAME = Regex("[0-9a-f]{16,}\\.wasm|[a-z]+-[A-Z0-9]{8}\\.(js|css)")
private val IMMUTABLE = CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public)

/**
 * The web app's files from [directory] under [path], with no token: they are the same public bundle the snapshot
 * release publishes, and the app asks for its token itself. For the app at [WEB_APP_PATH] `/` opens it; the client at
 * [WEB2_APP_PATH] leaves `/` alone. Hashed files are cached for good; the rest revalidate, so a new image's bundle
 * shows on the next load.
 */
fun Route.webAppRoutes(directory: Path, path: String = WEB_APP_PATH) {
    if (path == WEB_APP_PATH) get("/") { call.respondRedirect("$path/") }
    route(path) {
        install(ConditionalHeaders)
        // The page's URLs are relative, so `/app` without the slash would resolve them against the root.
        get { call.respondRedirect("$path/") }
        staticFiles("/", directory.toFile()) {
            preCompressed(CompressedFileType.GZIP)
            contentType { file -> if (file.extension == "wasm") ContentType("application", "wasm") else null }
            cacheControl { file ->
                listOf(if (HASHED_NAME.matches(file.name)) IMMUTABLE else CacheControl.NoCache(null))
            }
            modify { _, call ->
                call.response.header("Content-Security-Policy", WEB_APP_CSP)
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header("Referrer-Policy", "no-referrer")
            }
        }
    }
}

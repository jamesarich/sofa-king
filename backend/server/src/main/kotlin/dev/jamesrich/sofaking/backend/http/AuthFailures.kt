package dev.jamesrich.sofaking.backend.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import java.time.Clock
import org.slf4j.LoggerFactory

private val authLog = LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.http.Auth")

/**
 * `auth failed route=... remote=...` lines for rejected tokens, at most [perMinute] in each clock minute; the first
 * line after a dropped one carries `suppressed=N`. The route is the routed path, never the query, and no token is logged.
 */
internal class AuthFailureLog(private val clock: Clock, private val perMinute: Int = 30, private val sink: (String) -> Unit = authLog::warn) {
    private var minute = Long.MIN_VALUE
    private var logged = 0
    private var suppressed = 0

    fun failed(route: String, remote: String) {
        val line = synchronized(this) {
            val now = clock.millis() / 60_000
            if (now != minute) { minute = now; logged = 0 }
            if (logged >= perMinute) { suppressed++; return }
            logged++
            val dropped = suppressed.also { suppressed = 0 }
            "auth failed route=$route remote=$remote" + if (dropped > 0) " suppressed=$dropped" else ""
        }
        sink(line)
    }
}

internal val AuthFailureLogKey = AttributeKey<AuthFailureLog>("AuthFailureLog")

/** Records a rejected token for this call in the application's [AuthFailureLog], when it has one. */
internal fun ApplicationCall.authFailed() {
    val route = routedPath(request.path()) ?: "-"
    application.attributes.getOrNull(AuthFailureLogKey)?.failed(route, request.origin.remoteAddress)
}

package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.library.WatchlistService
import dev.jamesrich.sofaking.contract.AddWatchlistEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** The household's watchlist under `/api/v1/watchlist`, behind the API token. */
internal fun Route.watchlistRoutes(watchlist: WatchlistService) {
    route("/api/v1/watchlist") {
        get { call.respond(watchlist.list()) }
        post { call.respond(HttpStatusCode.Created, watchlist.add(call.appBody<AddWatchlistEntry>())) }
        delete("/{id}") {
            watchlist.remove(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

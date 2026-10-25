package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** What On Later lists: the movies, sports, kids' programs or premieres coming up, as the server picks them. */
enum class OnLaterKind(internal val wire: String) {
    MOVIES(Wire.OnLaterKind.MOVIE),
    SPORTS(Wire.OnLaterKind.SPORTS),
    KIDS(Wire.OnLaterKind.KIDS),
    PREMIERES(Wire.OnLaterKind.NEW),
}

/** On Later from the server [apis] names, on one that offers it. */
class OnLaterBook internal constructor(private val apis: Flow<SofaKingApi?>) {
    /** The programs of [kind] that start within [days] days and have not ended, soonest first; null when not read. */
    suspend fun programs(kind: OnLaterKind, days: Int = DEFAULT_DAYS): List<Program>? {
        val api = apis.first() ?: return null
        return runCatchingCancellable { SofaKingMapper.upcoming(api.onLater(kind.wire, days)) }
            .onFailure { e -> log.w { "reading On Later failed: ${e.describe()}" } }
            .getOrNull()
    }

    private companion object {
        val log = Logger.withTag("SofaKing")

        /** Today and tomorrow, which is what a viewer plans an evening around. */
        const val DEFAULT_DAYS = 2
    }
}

internal suspend fun SofaKingApi.onLater(kind: String, days: Int): UpcomingProgramsDto = call {
    http.get("guide/on-later") {
        parameter("kind", kind)
        parameter("days", days)
    }.decode()
}

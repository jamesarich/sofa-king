package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jamesrich.sofaking.data.FakeHttp
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.data.apiError
import dev.jamesrich.sofaking.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * A fake server from a route table: [handler] answers a request's method, path and body with a status and a JSON
 * body, and a request it leaves unanswered gets a 404.
 */
internal class RouteServer(
    scope: TestScope,
    handler: suspend (method: String, path: String, body: String) -> Pair<Int, String>?,
) {
    val http = FakeHttp(scope) { request ->
        handler(request.method, request.path, request.body)?.let { (status, body) -> json(body, status) }
            ?: apiError(404, "RESOURCE_NOT_FOUND")
    }

    /** Every request so far, as `METHOD /path body`. */
    val requests get() = http.requests.map { it.toString() }
}

/**
 * What the ViewModel tests share: [viewModelTest] runs with Main on the test's scheduler, as `viewModelScope` needs,
 * and stops every ViewModel the test [started] as it ends, pass or fail. Their loops run on virtual time, where a
 * long delay costs nothing, so one left running would keep the test draining without end. Main stays set while
 * runTest winds the test down; the class's `@AfterTest` calls [tearDown] to reset it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ViewModelFixture {
    private val started = mutableListOf<ViewModel>()

    fun viewModelTest(block: suspend TestScope.() -> Unit): TestResult = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            started.forEach { it.viewModelScope.cancel() }
            started.clear()
        }
    }

    fun tearDown() = Dispatchers.resetMain()

    /** [model], stopped as the test ends. */
    fun <T : ViewModel> started(model: T): T = model.also { started += it }

    /** The app's one session, configured on [api] as the connection does, over [scope]'s background scope. */
    suspend fun sessions(scope: TestScope, api: SofaKingApi?): PlaybackSessions =
        PlaybackSessions(scope.backgroundScope).also { it.configure(api) }
}

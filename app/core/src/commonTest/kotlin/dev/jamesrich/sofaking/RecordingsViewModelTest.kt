package dev.jamesrich.sofaking

import androidx.lifecycle.viewModelScope
import dev.jamesrich.sofaking.data.CommercialBreak
import dev.jamesrich.sofaking.data.FakeHttp
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.RecordingSpace
import dev.jamesrich.sofaking.data.RecordingState
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.data.apiError
import dev.jamesrich.sofaking.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun recording(id: String, state: String, extra: String = "") =
        """{"id":"$id","channelId":"c1","channelName":"News 4","title":"Evening News","startsAt":"2026-10-18T18:00:00Z",""" +
            """"endsAt":"2026-10-18T18:30:00Z","state":"$state"$extra}"""

    private class Server(scope: TestScope, val handler: suspend (String, String, String) -> Pair<Int, String>?) {
        val http = FakeHttp(scope) { request ->
            handler(request.method, request.path, request.body)?.let { (status, body) -> json(body, status) } ?: apiError(404, "RESOURCE_NOT_FOUND")
        }
        val requests get() = http.requests.map { it.toString() }
    }

    private suspend fun TestScope.viewModel(server: Server, changed: suspend () -> Unit = {}, api: SofaKingApi? = server.http.api()): RecordingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sessions = PlaybackSessions(backgroundScope)
        sessions.configure(api)
        return RecordingsViewModel(MutableStateFlow(api), sessions, changed, timeSource = testScheduler.timeSource, saveInterval = 10.seconds)
    }

    private val tv = PlaybackProfile(listOf("mp4"), listOf("h264"), listOf("aac"))

    @Test
    fun `the list maps each recording with its breaks and the folder's space`() = runTest {
        val server = Server(this) { method, path, _ ->
            if (method == "GET" && path == "/api/v1/recordings") 200 to """{"recordings":[
                ${recording("r1", "completed", ""","episodeTitle":"","bytes":2000,"positionMs":60000,"breaks":[{"startMs":900000,"endMs":1080000},{"startMs":30000,"endMs":90000},{"startMs":5,"endMs":5}]""")},
                ${recording("r2", "archived")},
                {"id":"bad","channelId":"c1","title":"x","startsAt":"soon","endsAt":"later","state":"scheduled"}],
                "storage":{"freeBytes":10,"totalBytes":100,"usedBytes":5}}""" else null
        }
        val model = viewModel(server)
        runCurrent()

        val ready = assertIs<RecordingsState.Ready>(model.state.value)
        assertEquals(RecordingSpace(10, 100), ready.space)
        assertEquals(listOf("r1", "r2"), ready.recordings.map { it.id }, "a recording with unreadable times is left out")
        val first = ready.recordings.first()
        assertEquals(null, first.episodeTitle)
        assertTrue(first.playable)
        assertTrue(first.inProgress)
        assertEquals(listOf(CommercialBreak(30_000, 90_000), CommercialBreak(900_000, 1_080_000)), first.breaks, "in order, empty ones dropped")
        assertEquals(RecordingState.OTHER, ready.recordings.last().state)
        assertFalse(ready.recordings.last().playable)
        model.viewModelScope.cancel()
    }

    @Test
    fun `a server that does not record says so`() = runTest {
        val model = viewModel(Server(this) { _, _, _ -> null })
        runCurrent()

        assertIs<RecordingsState.Unavailable>(model.state.value)
        model.viewModelScope.cancel()
    }

    @Test
    fun `deleting a recording drops it and tells the guide`() = runTest {
        var deleted = false
        var told = 0
        val server = Server(this) { method, path, _ ->
            when {
                method == "DELETE" && path == "/api/v1/recordings/r1" -> { deleted = true; 204 to "" }
                method == "GET" -> 200 to (if (deleted) """{"recordings":[]}""" else """{"recordings":[${recording("r1", "completed")}]}""")
                else -> null
            }
        }
        val model = viewModel(server, changed = { told++ })
        runCurrent()

        val recording = model.recording("r1")!!
        assertTrue(model.delete(recording))
        runCurrent()

        assertEquals(emptyList(), assertIs<RecordingsState.Ready>(model.state.value).recordings)
        assertEquals(1, told)
        model.viewModelScope.cancel()
    }

    @Test
    fun `playing resumes where the household stopped and the end marks it watched`() = runTest {
        val server = Server(this) { method, path, body ->
            when {
                method == "GET" && path == "/api/v1/recordings" ->
                    200 to """{"recordings":[${recording("r1", "completed", ""","positionMs":120000""")}]}"""
                method == "POST" && path == "/api/v1/vod-sessions" -> 201 to
                    """{"id":"v1","itemId":"","recordingId":"r1","mode":"remux","startMs":120000,"durationMs":1800000,""" +
                    """"descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/vod/v1/1/k/index.m3u8","epoch":1}}"""
                method == "PATCH" && path == "/api/v1/recordings/r1" -> 200 to recording("r1", "completed", if ("watched" in body) ""","watched":true""" else "")
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        val stream = model.play(model.recording("r1")!!, tv)
        assertEquals("r1", stream.itemId)
        assertTrue(server.requests.any { it.startsWith("POST /api/v1/vod-sessions") && "\"startMs\":120000" in it && "\"recordingId\":\"r1\"" in it }, server.requests.toString())

        model.progress("r1", 130_000, 1_800_000)
        advanceTimeBy(11.seconds)
        model.progress("r1", 141_000, 1_800_000)
        runCurrent()
        assertEquals("""PATCH /api/v1/recordings/r1 {"positionMs":141000}""", server.requests.last())

        model.stopped("r1", 1_700_000, 1_800_000)
        runCurrent()
        assertEquals("""PATCH /api/v1/recordings/r1 {"positionMs":0,"watched":true}""", server.requests.last())
        assertTrue(model.recording("r1")!!.watched)
        model.viewModelScope.cancel()
    }
}

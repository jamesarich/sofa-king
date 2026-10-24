package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class HttpTest {
    // A browser's failed fetch surfaces as a plain Error, so the engine here throws one.
    @Suppress("TooGenericExceptionThrown")
    @Test
    fun `a failed browser fetch is an IOException and an Error elsewhere stays one`() = runTest {
        val client = httpClient(MockEngine { throw Error("Fail to fetch") })

        val e = assertFails { client.get("http://server.test/api/v1/channels") }

        if (engineFailsWithErrors) assertTrue(e is IOException) else assertTrue(e !is IOException)
    }
}

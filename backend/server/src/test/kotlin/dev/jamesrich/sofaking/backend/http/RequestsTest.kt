package dev.jamesrich.sofaking.backend.http

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RequestsTest {
    @Test fun `a decoder that reads past the end of a body fails as a SerializationException`() {
        val failure = assertFailsWith<SerializationException> {
            decodingRequest<Unit> { throw ArrayIndexOutOfBoundsException("Index 14 out of bounds for length 14") }
        }
        assertIs<ArrayIndexOutOfBoundsException>(failure.cause)
    }

    @Test fun `a decoded body and any other failure pass through`() {
        assertEquals(7, decodingRequest { 7 })
        assertFailsWith<IllegalStateException> { decodingRequest<Unit> { error("boom") } }
    }
}

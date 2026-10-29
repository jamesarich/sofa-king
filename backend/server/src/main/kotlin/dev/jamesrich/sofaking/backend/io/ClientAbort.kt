package dev.jamesrich.sofaking.backend.io

import io.ktor.util.cio.ChannelIOException
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ConnectionClosedException

/** A write or read that failed because the client's connection is gone, as Ktor, Netty and the JDK each report it. */
internal fun clientAbort(cause: Throwable): Boolean {
    val causes = generateSequence(cause) { it.cause }.take(4)
    return causes.any { error ->
        error is ClosedByteChannelException || error is ConnectionClosedException || error is ChannelIOException ||
            error is java.nio.channels.ClosedChannelException ||
            (error is java.io.IOException && error.message?.let { ioAbortMessage.containsMatchIn(it) } == true)
    }
}

private val ioAbortMessage = Regex("(?i)broken pipe|connection reset|connection or inbound has closed")

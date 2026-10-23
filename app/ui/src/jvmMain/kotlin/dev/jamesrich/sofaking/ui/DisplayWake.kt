package dev.jamesrich.sofaking.ui

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.IntByReference

private val log = Logger.withTag("SofaKing")

/**
 * Keeps the display awake while a screen plays, through the OS's power API and the JNA that vlcj brings: the picture
 * is drawn by the app, not by a system video view the OS would keep the display awake for. Linux has no such call
 * short of D-Bus, which the app does not carry, so there the desktop's own idle rules apply.
 */
internal val displayWake: WakeHolds? = when {
    Platform.isWindows() -> WakeHolds(acquire = ::keepWindowsAwake, release = ::letWindowsSleep)
    Platform.isMac() -> WakeHolds(acquire = ::keepMacAwake, release = ::letMacSleep)
    else -> null
}

// The execution state belongs to the thread that sets it; composition sets and clears it on the UI thread.
private fun keepWindowsAwake() = guarded {
    Kernel32.INSTANCE.SetThreadExecutionState(WinBase.ES_CONTINUOUS or WinBase.ES_DISPLAY_REQUIRED)
}

private fun letWindowsSleep() = guarded { Kernel32.INSTANCE.SetThreadExecutionState(WinBase.ES_CONTINUOUS) }

private var macAssertion: Int? = null

private fun keepMacAwake() = guarded {
    val type = CFStringRef.createCFString("PreventUserIdleDisplaySleep")
    val name = CFStringRef.createCFString("Sofa King is playing")
    try {
        val id = IntByReference()
        if (PowerAssertions.INSTANCE.IOPMAssertionCreateWithName(type, ASSERTION_LEVEL_ON, name, id) == 0) {
            macAssertion = id.value
        }
    } finally {
        type.release()
        name.release()
    }
}

private fun letMacSleep() = guarded {
    macAssertion?.let(PowerAssertions.INSTANCE::IOPMAssertionRelease)
    macAssertion = null
}

/** A missing or refusing power API costs only the display staying awake, never playback. */
private inline fun guarded(call: () -> Unit) {
    try {
        call()
    } catch (e: Throwable) {
        log.w(e) { "keeping the display awake failed" }
    }
}

/** IOKit's power assertions, which the JNA platform library does not map. */
@Suppress("FunctionName")
private interface PowerAssertions : Library {
    fun IOPMAssertionCreateWithName(type: CFStringRef, level: Int, name: CFStringRef, id: IntByReference): Int

    fun IOPMAssertionRelease(id: Int): Int

    companion object {
        val INSTANCE: PowerAssertions by lazy { Native.load("IOKit", PowerAssertions::class.java) }
    }
}

private const val ASSERTION_LEVEL_ON = 255

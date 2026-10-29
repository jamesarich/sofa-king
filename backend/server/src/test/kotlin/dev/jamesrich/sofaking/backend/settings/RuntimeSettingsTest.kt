package dev.jamesrich.sofaking.backend.settings

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveRuntimeSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeSettingsTest {
    private val dirs = mutableListOf<Path>()
    private val clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC)
    private val environment = runtimeValues("info", 3, 60, 2, 4)
    private fun runtimeValues(level: String, days: Int, plex: Int, tuners: Int, sessions: Int) = RuntimeValues(
        RuntimeValues.DEFAULT.byName() + mapOf(
            Settings.LOG_LEVEL.name to level,
            Settings.XMLTV_DAYS.name to days.toString(),
            Settings.PLEX_SYNC_MINUTES.name to plex.toString(),
            Settings.HDHR_TUNERS.name to tuners.toString(),
            Settings.MAX_SESSIONS.name to sessions.toString(),
        ),
    )

    private fun directory(): Path = Files.createTempDirectory("sofa-settings-test-").also { dirs.add(it) }

    @AfterTest fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test fun `saved values override the environment, notify listeners and survive a reopen`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            val settings = RuntimeSettings(store, environment, clock)
            assertTrue(settings.load().isEmpty())
            assertEquals(environment, settings.current)
            val seen = mutableListOf<RuntimeValues>()
            settings.onChange { seen += it }
            assertEquals(listOf(environment), seen, "a listener runs once on registration")

            val next = settings.update(
                environment.byName() + mapOf(Settings.XMLTV_DAYS.name to "7", Settings.MAX_SESSIONS.name to "6"),
            )
            assertEquals(runtimeValues("info", 7, 60, 2, 6), next)
            assertEquals(next, settings.current)
            assertEquals(
                setOf(Settings.XMLTV_DAYS.name, Settings.MAX_SESSIONS.name),
                settings.saved,
                "values equal to the environment's are not saved",
            )
            assertEquals(listOf(environment, next), seen)

            settings.update(next.byName())
            assertEquals(listOf(environment, next), seen, "an unchanged save notifies nobody")
        }
        openStore(dir).use { store ->
            val settings = RuntimeSettings(store, environment, clock)
            assertTrue(settings.load().isEmpty())
            assertEquals(runtimeValues("info", 7, 60, 2, 6), settings.current)
            assertEquals(setOf(Settings.XMLTV_DAYS.name, Settings.MAX_SESSIONS.name), settings.saved)
            assertEquals(environment, settings.update(emptyMap()), "an empty save goes back to the environment")
            assertTrue(settings.saved.isEmpty())
        }
    }

    @Test fun `an invalid value refuses the whole save and a saved value that no longer fits is dropped at load`() =
        runBlocking<Unit> {
            val dir = directory()
            openStore(dir).use { store ->
                val settings = RuntimeSettings(store, environment, clock)
                val refused = assertFailsWith<ApiFailure> {
                    settings.update(
                        mapOf(
                            Settings.XMLTV_DAYS.name to "7",
                            Settings.HDHR_TUNERS.name to "9",
                        ),
                    )
                }
                assertEquals("SOFA_HDHR_TUNERS must be between 1 and SOFA_MAX_SESSIONS (4)", refused.message)
                assertEquals(environment, settings.current, "nothing of a refused save applies")
                assertFailsWith<ApiFailure> { settings.update(mapOf(Settings.LOG_LEVEL.name to "loud")) }
                assertFailsWith<ApiFailure> { settings.update(mapOf(Settings.XMLTV_DAYS.name to "15")) }
                assertFailsWith<ApiFailure> { settings.update(mapOf(Settings.PLEX_SYNC_MINUTES.name to "x")) }
                assertEquals(
                    "SOFA_LEARNED_BREAKS must be one of off, shadow, on",
                    assertFailsWith<ApiFailure> {
                        settings.update(mapOf(Settings.LEARNED_BREAKS.name to "maybe"))
                    }.message,
                )
                assertEquals("on", settings.update(mapOf(Settings.LEARNED_BREAKS.name to " ON")).learnedBreaks)
                settings.update(emptyMap())

                // A value saved by an older server, or by hand, that is out of range now.
                store.saveRuntimeSettings(
                    mapOf(Settings.XMLTV_DAYS.name to "40", Settings.LOG_LEVEL.name to "debug"),
                    clock.instant(),
                )
                val problems = settings.load()
                assertEquals(listOf("SOFA_XMLTV_DAYS must be between 1 and 14"), problems)
                assertEquals(runtimeValues("debug", 3, 60, 2, 4), settings.current)
                assertEquals(setOf(Settings.LOG_LEVEL.name), settings.saved)
            }
        }

    @Test fun `tuners saved with a higher session cap survive a restart`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            RuntimeSettings(store, environment, clock).update(
                mapOf(
                    Settings.MAX_SESSIONS.name to "8",
                    Settings.HDHR_TUNERS.name to "6",
                ),
            )
        }
        openStore(dir).use { store ->
            val settings = RuntimeSettings(store, environment, clock)
            assertTrue(settings.load().isEmpty())
            assertEquals(runtimeValues("info", 3, 60, 6, 8), settings.current)
        }
    }
}

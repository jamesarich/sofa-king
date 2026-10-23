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
    private val environment = RuntimeValues("info", 3, 60, 2, 4)
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
                environment.byName() + mapOf(RuntimeValues.GUIDE_DAYS to "7", RuntimeValues.MAX_SESSIONS to "6"),
            )
            assertEquals(RuntimeValues("info", 7, 60, 2, 6), next)
            assertEquals(next, settings.current)
            assertEquals(
                setOf(RuntimeValues.GUIDE_DAYS, RuntimeValues.MAX_SESSIONS),
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
            assertEquals(RuntimeValues("info", 7, 60, 2, 6), settings.current)
            assertEquals(setOf(RuntimeValues.GUIDE_DAYS, RuntimeValues.MAX_SESSIONS), settings.saved)
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
                            RuntimeValues.GUIDE_DAYS to "7",
                            RuntimeValues.HDHR_TUNERS to "9",
                        ),
                    )
                }
                assertEquals("SOFA_HDHR_TUNERS must be between 1 and SOFA_MAX_SESSIONS (4)", refused.message)
                assertEquals(environment, settings.current, "nothing of a refused save applies")
                assertFailsWith<ApiFailure> { settings.update(mapOf(RuntimeValues.LOG_LEVEL to "loud")) }
                assertFailsWith<ApiFailure> { settings.update(mapOf(RuntimeValues.GUIDE_DAYS to "15")) }
                assertFailsWith<ApiFailure> { settings.update(mapOf(RuntimeValues.PLEX_SYNC_MINUTES to "x")) }
                assertEquals(
                    "SOFA_LEARNED_BREAKS must be one of off, shadow, on",
                    assertFailsWith<ApiFailure> {
                        settings.update(mapOf(RuntimeValues.LEARNED_BREAKS to "maybe"))
                    }.message,
                )
                assertEquals("on", settings.update(mapOf(RuntimeValues.LEARNED_BREAKS to " ON")).learnedBreaks)
                settings.update(emptyMap())

                // A value saved by an older server, or by hand, that is out of range now.
                store.saveRuntimeSettings(
                    mapOf(RuntimeValues.GUIDE_DAYS to "40", RuntimeValues.LOG_LEVEL to "debug"),
                    clock.instant(),
                )
                val problems = settings.load()
                assertEquals(listOf("SOFA_XMLTV_DAYS must be between 1 and 14"), problems)
                assertEquals(RuntimeValues("debug", 3, 60, 2, 4), settings.current)
                assertEquals(setOf(RuntimeValues.LOG_LEVEL), settings.saved)
            }
        }

    @Test fun `tuners saved with a higher session cap survive a restart`() = runBlocking<Unit> {
        val dir = directory()
        openStore(dir).use { store ->
            RuntimeSettings(store, environment, clock).update(
                mapOf(
                    RuntimeValues.MAX_SESSIONS to "8",
                    RuntimeValues.HDHR_TUNERS to "6",
                ),
            )
        }
        openStore(dir).use { store ->
            val settings = RuntimeSettings(store, environment, clock)
            assertTrue(settings.load().isEmpty())
            assertEquals(RuntimeValues("info", 3, 60, 6, 8), settings.current)
        }
    }
}

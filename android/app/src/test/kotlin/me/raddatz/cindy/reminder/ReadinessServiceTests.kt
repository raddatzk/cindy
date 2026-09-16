package me.raddatz.cindy.reminder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.health.HealthMetrics
import me.raddatz.cindy.core.persistence.HistoryStore
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.reminder.NextSession
import me.raddatz.cindy.health.HealthError
import me.raddatz.cindy.health.HealthException
import me.raddatz.cindy.health.WorkoutExporter
import me.raddatz.cindy.workout.WorkoutHistory
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReadinessServiceTests {
    private val now = Instant.parse("2026-09-16T08:00:00Z")
    private val zone = ZoneId.of("Europe/Berlin")

    private class FakeReminder : ReminderScheduling {
        val scheduled = mutableListOf<NextSession>()
        var cancelled = 0

        override suspend fun schedule(session: NextSession, now: Instant) {
            scheduled += session
        }

        override fun cancel() {
            cancelled += 1
        }

        override suspend fun pendingDate(): Instant? = scheduled.lastOrNull()?.date
    }

    private val history = listOf(
        WorkoutRecord(
            date = Instant.parse("2026-09-15T17:20:00Z"),
            rounds = 12,
            extraReps = 3,
            durationSeconds = 1_200.0,
            completed = true,
            plan = WorkoutPlan.cindy,
        ),
    )

    private val healthReads = mutableListOf<Boolean>()
    private val reminder = FakeReminder()

    private fun service(sync: Boolean, reminders: Boolean, records: List<WorkoutRecord> = history) = ReadinessService(
        history = { records },
        health = { _, inBackground ->
            healthReads += inBackground
            HealthMetrics(sleepHours = 8.0)
        },
        reminder = reminder,
        syncToHealth = { sync },
        remindersEnabled = { reminders },
        clock = Clock.fixed(now, ZoneOffset.UTC),
        zone = { zone },
    )

    @Test
    fun withoutTheSyncHealthIsNotTouched() = runTest {
        val snapshot = service(sync = false, reminders = true).refresh()
        assertTrue(healthReads.isEmpty())
        val readiness = assertNotNull(snapshot.readiness)
        assertTrue(!readiness.usesHealthData)
        val next = assertNotNull(snapshot.nextSession)
        assertEquals(listOf(next), reminder.scheduled)
        // Ready 48 h after the session (17th, 19:20 in Berlin); the default 18:00 slot is within the 2 h grace.
        assertEquals(Instant.parse("2026-09-17T16:00:00Z"), next.date)
    }

    @Test
    fun backgroundRefreshesPassTheFlagOnAndUseHealthData() = runTest {
        val service = service(sync = true, reminders = true)
        val snapshot = service.refresh(inBackground = true)
        assertEquals(listOf(true), healthReads)
        assertTrue(snapshot.readiness!!.usesHealthData)
        assertEquals(snapshot, service.snapshot.value)
    }

    @Test
    fun disabledRemindersAndAnEmptyHistoryCancel() = runTest {
        service(sync = false, reminders = false).refresh()
        assertEquals(1, reminder.cancelled)
        val empty = service(sync = false, reminders = true, records = emptyList()).refresh()
        assertNull(empty.readiness)
        assertNull(empty.nextSession)
        assertEquals(2, reminder.cancelled)
        assertTrue(reminder.scheduled.isEmpty())
    }

    @Test
    fun savingAWorkoutRefreshesAndExportsOnlyWithTheSync() = runTest {
        val directory = Files.createTempDirectory("cindy-history").toFile()
        val exported = mutableListOf<WorkoutRecord>()
        var refreshes = 0
        var sync = false
        val exporter = object : WorkoutExporter {
            override suspend fun export(record: WorkoutRecord): Boolean {
                exported += record
                throw HealthException(HealthError.DENIED) // must not cost the user the result
            }
        }
        val workouts = historyFor(directory, exporter, { sync }, { refreshes += 1 })

        workouts.save(history[0])
        runCurrent()
        assertEquals(history, workouts.records.value)
        assertEquals(1, refreshes)
        assertTrue(exported.isEmpty())

        sync = true
        val second = history[0].copy(id = java.util.UUID.randomUUID(), date = now, completed = false)
        workouts.save(second)
        runCurrent()
        assertEquals(listOf(second, history[0]), workouts.records.value)
        assertEquals(listOf(second), exported)
        assertEquals(2, refreshes)
        assertEquals(history[0], workouts.lastCompletedRecord)

        workouts.delete(second)
        assertEquals(history, workouts.records.value)
        directory.deleteRecursively()
    }

    private fun TestScope.historyFor(
        directory: File,
        exporter: WorkoutExporter,
        sync: () -> Boolean,
        refresh: suspend () -> Unit,
    ) = WorkoutHistory(
        store = HistoryStore(File(directory, "history.json")),
        exporter = exporter,
        syncToHealth = sync,
        refreshReadiness = refresh,
        scope = TestScope(StandardTestDispatcher(testScheduler)),
    )
}

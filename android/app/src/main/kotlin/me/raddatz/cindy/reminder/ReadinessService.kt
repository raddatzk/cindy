package me.raddatz.cindy.reminder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.cindy.core.health.HealthMetrics
import me.raddatz.cindy.core.health.Readiness
import me.raddatz.cindy.core.health.ReadinessEstimator
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.reminder.NextSession
import me.raddatz.cindy.core.reminder.NextSessionPlanner
import me.raddatz.cindy.health.HealthMetricsSource
import java.time.Clock
import java.time.ZoneId

/** The latest readiness estimate and the session it suggests. */
data class ReadinessSnapshot(
    /** `null` when nothing at all is known yet. */
    val readiness: Readiness?,
    /** When the next session is suggested; also what the reminder is set to. */
    val nextSession: NextSession?,
)

/**
 * Recomputes readiness and rewrites the pending reminder (iOS: `AppModel.refreshReadiness` and
 * `refreshReminder`). Shared by the UI and the periodic background worker.
 *
 * Health data is only read while the sync is on — with the switch off Cindy does not touch the
 * health store at all and the estimate rests on the workout history.
 */
class ReadinessService(
    private val history: () -> List<WorkoutRecord>,
    private val health: HealthMetricsSource,
    private val reminder: ReminderScheduling,
    private val syncToHealth: () -> Boolean,
    private val remindersEnabled: () -> Boolean,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(ReadinessSnapshot(null, null))
    val snapshot: StateFlow<ReadinessSnapshot> = _snapshot.asStateFlow()

    /** @param inBackground called from the periodic worker (restricted health reads). */
    suspend fun refresh(inBackground: Boolean = false): ReadinessSnapshot = mutex.withLock {
        val now = clock.instant()
        val records = history()
        val metrics = if (syncToHealth()) health.readMetrics(now, inBackground) else HealthMetrics.none
        val readiness = ReadinessEstimator(now).estimate(records, metrics)
        updateReminder(records, readiness)
    }

    /** Recomputes the suggested date for the last readiness and rewrites the pending reminder. */
    suspend fun refreshReminder(): ReadinessSnapshot = mutex.withLock {
        updateReminder(history(), _snapshot.value.readiness)
    }

    private suspend fun updateReminder(records: List<WorkoutRecord>, readiness: Readiness?): ReadinessSnapshot {
        val now = clock.instant()
        val next = NextSessionPlanner(zone = zone()).plan(records, readiness, now)
        if (remindersEnabled() && next != null) {
            reminder.schedule(next, now)
        } else {
            reminder.cancel()
        }
        return ReadinessSnapshot(readiness, next).also { _snapshot.value = it }
    }
}

package me.raddatz.cindy.workout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.raddatz.cindy.core.persistence.HistoryStore
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.health.WorkoutExporter

/**
 * The saved workouts plus what saving one sets off (iOS: `AppModel.save` / `delete`): the readiness
 * refresh and, while the sync is on, the Health Connect export.
 */
class WorkoutHistory(
    private val store: HistoryStore,
    private val exporter: WorkoutExporter,
    private val syncToHealth: () -> Boolean,
    private val refreshReadiness: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    private val _records = MutableStateFlow<List<WorkoutRecord>>(emptyList())

    /** Newest first. */
    val records: StateFlow<List<WorkoutRecord>> = _records.asStateFlow()

    /** Most recent completed workout (for the result comparison). */
    val lastCompletedRecord: WorkoutRecord? get() = _records.value.firstOrNull { it.completed }

    fun reload() {
        _records.value = store.load()
    }

    fun save(record: WorkoutRecord) {
        try {
            store.append(record)
        } catch (_: Exception) {
            // Like iOS `try?`: the result screen stays usable even when the disk is full.
        }
        reload()
        scope.launch { refreshReadiness() }
        if (!syncToHealth()) return
        // Fire and forget: a failed health write must not cost the user the result.
        scope.launch {
            try {
                exporter.export(record)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun delete(record: WorkoutRecord) {
        try {
            store.delete(record.id)
        } catch (_: Exception) {
        }
        reload()
    }
}

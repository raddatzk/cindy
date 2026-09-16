package me.raddatz.cindy.core.workout

import kotlinx.serialization.Serializable
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.WorkoutPlan

enum class WorkoutPhase {
    IDLE,
    COUNTDOWN,

    /** Counting reps of the current exercise. */
    ACTIVE,

    /** Last rep of an exercise done; waiting for the next exercise's signal to stabilise. */
    TRANSITION,
    PAUSED,
    FINISHED,
}

sealed interface WorkoutEvent {
    data object Started : WorkoutEvent
    data class RepCounted(val exercise: Exercise, val count: Int) : WorkoutEvent
    data class RepRemoved(val exercise: Exercise, val count: Int) : WorkoutEvent
    data class ExerciseCompleted(val exercise: Exercise, val next: Exercise) : WorkoutEvent
    data class ExerciseReopened(val exercise: Exercise) : WorkoutEvent
    data class ExerciseStarted(val exercise: Exercise) : WorkoutEvent
    data class RoundCompleted(val round: Int) : WorkoutEvent
    data class RoundReopened(val round: Int) : WorkoutEvent
    data object Finished : WorkoutEvent
}

/** CrossFit notation: full rounds plus reps of the unfinished round ("14 + 7"). */
@Serializable
data class WorkoutScore(
    val rounds: Int,
    val reps: Int,
    val repsPerRound: Int = WorkoutPlan.cindy.repsPerRound,
) {
    /** Language-neutral: "14 + 7", or "14" without extra reps. */
    val notation: String get() = if (reps == 0) "$rounds" else "$rounds + $reps"
    val totalReps: Int get() = rounds * repsPerRound + reps
}

/**
 * Pure state machine for the AMRAP: exercise order, rep counter, rounds and manual corrections.
 * Time is handled by the engine that drives it. Not thread-safe.
 */
class WorkoutStateMachine(plan: WorkoutPlan = WorkoutPlan.cindy) {
    var plan: WorkoutPlan = plan
        private set
    var phase: WorkoutPhase = WorkoutPhase.IDLE
        private set
    var exercise: Exercise = plan.first
        private set
    var repCount: Int = 0
        private set
    var completedRounds: Int = 0
        private set

    val score: WorkoutScore
        get() = WorkoutScore(completedRounds, plan.repsBefore(exercise) + repCount, plan.repsPerRound)

    /** Round number shown to the athlete (1-based, the one currently in progress). */
    val currentRound: Int get() = completedRounds + 1

    val isRunning: Boolean get() = phase == WorkoutPhase.ACTIVE || phase == WorkoutPhase.TRANSITION

    // Lifecycle

    fun beginCountdown() {
        if (phase != WorkoutPhase.IDLE) return
        phase = WorkoutPhase.COUNTDOWN
    }

    /** Countdown finished: the first exercise waits for its signal to stabilise. */
    fun start(): List<WorkoutEvent> {
        if (!(phase == WorkoutPhase.COUNTDOWN || phase == WorkoutPhase.IDLE)) return emptyList()
        phase = WorkoutPhase.TRANSITION
        return listOf(WorkoutEvent.Started)
    }

    /** The next exercise's signal is stable; reps count from now on. */
    fun activate(): List<WorkoutEvent> {
        if (phase != WorkoutPhase.TRANSITION) return emptyList()
        phase = WorkoutPhase.ACTIVE
        return listOf(WorkoutEvent.ExerciseStarted(exercise))
    }

    fun pause() {
        if (!isRunning) return
        phase = WorkoutPhase.PAUSED
    }

    fun resume() {
        if (phase != WorkoutPhase.PAUSED) return
        // Always come back through a transition so the detector re-arms.
        phase = WorkoutPhase.TRANSITION
    }

    fun finish(): List<WorkoutEvent> {
        if (phase == WorkoutPhase.FINISHED || phase == WorkoutPhase.IDLE) return emptyList()
        phase = WorkoutPhase.FINISHED
        return listOf(WorkoutEvent.Finished)
    }

    // Changing the plan

    /**
     * Swaps the plan mid-workout. Completed rounds stay. The current exercise keeps its reps and
     * is done at once if they already reach the new target. If it was removed, the round goes on
     * with the first exercise of the new plan not yet done in this round, or counts as complete
     * when nothing is left.
     */
    fun replacePlan(newPlan: WorkoutPlan): List<WorkoutEvent> {
        if (!newPlan.isValid || phase == WorkoutPhase.FINISHED) return emptyList()
        val doneThisRound = plan.exercises.takeWhile { it != exercise }
        plan = newPlan
        if (newPlan.contains(exercise)) {
            return if (repCount >= newPlan.countTarget(exercise)) completeExercise() else emptyList()
        }
        repCount = 0
        if (phase == WorkoutPhase.ACTIVE) {
            phase = WorkoutPhase.TRANSITION
        }
        val next = newPlan.exercises.firstOrNull { it !in doneThisRound }
        if (next != null) {
            exercise = next
            return emptyList()
        }
        completedRounds += 1
        exercise = newPlan.first
        return listOf(WorkoutEvent.RoundCompleted(completedRounds))
    }

    // Counting

    /** A rep detected by the signal chain (only accepted while active). */
    fun registerRep(): List<WorkoutEvent> {
        if (phase != WorkoutPhase.ACTIVE) return emptyList()
        return increment()
    }

    /** Manual +1 / −1 correction; allowed while active, in transition or paused. */
    fun adjust(by: Int): List<WorkoutEvent> {
        if (!(phase == WorkoutPhase.ACTIVE || phase == WorkoutPhase.TRANSITION || phase == WorkoutPhase.PAUSED)) {
            return emptyList()
        }
        return when (by) {
            1 -> increment()
            -1 -> decrement()
            else -> emptyList()
        }
    }

    private fun increment(): List<WorkoutEvent> {
        repCount += 1
        val events = mutableListOf<WorkoutEvent>(WorkoutEvent.RepCounted(exercise, repCount))
        if (repCount >= plan.countTarget(exercise)) {
            events += completeExercise()
        }
        return events
    }

    private fun completeExercise(): List<WorkoutEvent> {
        val finished = exercise
        val events = mutableListOf<WorkoutEvent>()
        if (plan.isLast(finished)) {
            completedRounds += 1
            events += WorkoutEvent.RoundCompleted(completedRounds)
        }
        exercise = plan.next(after = finished)
        repCount = 0
        events += WorkoutEvent.ExerciseCompleted(finished, next = exercise)
        if (phase == WorkoutPhase.ACTIVE) {
            phase = WorkoutPhase.TRANSITION
        }
        return events
    }

    private fun decrement(): List<WorkoutEvent> {
        if (repCount > 0) {
            repCount -= 1
            return listOf(WorkoutEvent.RepRemoved(exercise, repCount))
        }
        // At 0: step back into the previous exercise (undo a wrongly completed exercise).
        if (!(completedRounds > 0 || !plan.isFirst(exercise))) return emptyList()
        val events = mutableListOf<WorkoutEvent>()
        val previous = plan.previous(before = exercise)
        if (plan.isLast(previous)) {
            completedRounds -= 1
            events += WorkoutEvent.RoundReopened(completedRounds + 1)
        }
        exercise = previous
        repCount = plan.countTarget(previous) - 1
        events += WorkoutEvent.ExerciseReopened(previous)
        events += WorkoutEvent.RepRemoved(previous, repCount)
        if (phase == WorkoutPhase.ACTIVE) {
            phase = WorkoutPhase.TRANSITION
        }
        return events
    }
}

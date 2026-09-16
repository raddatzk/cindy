package me.raddatz.cindy.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.cindy.CindyApplication
import me.raddatz.cindy.core.reminder.NextSession
import me.raddatz.cindy.core.reminder.NextSessionReason
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Scheduling of the one pending nudge; [ReminderScheduler] in the app, a fake in tests. */
interface ReminderScheduling {
    /** Replaces the pending nudge with one at [NextSession.date] (nothing when that has passed or notifications are off). */
    suspend fun schedule(session: NextSession, now: Instant = Instant.now())

    fun cancel()

    /** When the pending nudge will fire, if one is scheduled. */
    suspend fun pendingDate(): Instant?
}

/**
 * The reminder on WorkManager (iOS: `WorkoutReminder` + the `BGAppRefreshTask`).
 *
 * Nothing leaves the device: a unique one-time work, delayed to the date the planner picked, posts
 * the notification. A pending work cannot re-evaluate itself, so it is replaced whenever readiness
 * is recomputed — at launch, after a workout, and from the periodic refresh.
 *
 * Timing is inexact by design: WorkManager may defer the work in Doze or battery saver, so the
 * nudge can arrive later than planned (it never needs an exact alarm permission). The periodic
 * refresh runs about every 12 hours, whenever the system allows — like iOS's `earliestBeginDate`.
 */
class ReminderScheduler(context: Context) : ReminderScheduling {
    private val appContext = context.applicationContext
    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    override suspend fun schedule(session: NextSession, now: Instant) {
        cancel()
        if (!session.date.isAfter(now) || !NotificationPermission.canPost(appContext)) return
        val delay = Duration.between(now, session.date)
        val request = OneTimeWorkRequestBuilder<ReminderNotificationWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(ReminderNotificationWorker.KEY_REASON, session.reason.name)
                    .putLong(ReminderNotificationWorker.KEY_DATE, session.date.toEpochMilli())
                    .build(),
            )
            .addTag(REMINDER_WORK)
            .build()
        workManager.enqueueUniqueWork(REMINDER_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(REMINDER_WORK)
    }

    override suspend fun pendingDate(): Instant? = withContext(Dispatchers.IO) {
        val infos = try {
            workManager.getWorkInfosForUniqueWork(REMINDER_WORK).get()
        } catch (_: Exception) {
            return@withContext null
        }
        infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.let { Instant.ofEpochMilli(it.nextScheduleTimeMillis) }
    }

    /** Keeps the periodic readiness refresh scheduled; call at app start. */
    fun ensurePeriodicRefresh() {
        val request = PeriodicWorkRequestBuilder<ReadinessRefreshWorker>(REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
            .setInitialDelay(REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        /** One unique name, so scheduling always replaces the previous nudge (iOS identifier). */
        const val REMINDER_WORK: String = "nextWorkout"
        const val REFRESH_WORK: String = "me.raddatz.cindy.refresh"
        const val REFRESH_INTERVAL_HOURS: Long = 12
    }
}

/** Posts the reminder notification when its delay is over. */
class ReminderNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON)
            ?.let { name -> NextSessionReason.entries.firstOrNull { it.name == name } }
            ?: NextSessionReason.RECOVERED
        ReminderNotifications.post(applicationContext, reason)
        return Result.success()
    }

    companion object {
        const val KEY_REASON = "reason"
        const val KEY_DATE = "date"
    }
}

/**
 * Recomputes readiness in the background and moves the pending nudge (iOS: the app-refresh
 * background task). Health Connect is only read when background reads are granted; otherwise the
 * estimate rests on the workout history.
 */
class ReadinessRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? CindyApplication)?.container ?: return Result.success()
        container.readiness.refresh(inBackground = true)
        return Result.success()
    }
}

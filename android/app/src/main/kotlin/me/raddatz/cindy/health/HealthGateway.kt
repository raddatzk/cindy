package me.raddatz.cindy.health

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.raddatz.cindy.R
import me.raddatz.cindy.core.health.HealthMetrics
import me.raddatz.cindy.core.health.HealthSeries
import me.raddatz.cindy.core.health.MetricBaseline
import me.raddatz.cindy.core.health.WorkoutHealthSample
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.settings.SettingsStore
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.reflect.KClass

/** Whether Health Connect can be used on this device. */
enum class HealthAvailability {
    AVAILABLE,

    /** Not supported here (no provider app, work profile, …). */
    UNAVAILABLE,

    /** Android 13 and lower: the Health Connect app is missing or too old; see [HealthGateway.providerUpdateIntent]. */
    UPDATE_REQUIRED,
}

/** Why a Health Connect write failed. User-facing text: [message]. */
enum class HealthError {
    /** "Health Connect is not available on this device." */
    UNAVAILABLE,

    /** "Cindy may not write to Health Connect. Allow it in the Health Connect settings." */
    DENIED,

    /** "The workout could not be written to Health Connect." */
    FAILED,
}

class HealthException(val error: HealthError, cause: Throwable? = null) : Exception(error.name, cause)

fun HealthError.message(context: Context): String = context.getString(
    when (this) {
        HealthError.UNAVAILABLE -> R.string.health_error_unavailable
        HealthError.DENIED -> R.string.health_error_denied
        HealthError.FAILED -> R.string.health_error_failed
    },
)

/** What the readiness refresh needs from the health store. */
fun interface HealthMetricsSource {
    /**
     * @param inBackground the read happens without a visible activity (the periodic worker); it
     *   is skipped unless background reads are granted.
     */
    suspend fun readMetrics(now: Instant, inBackground: Boolean): HealthMetrics
}

/** Writes finished workouts to the health store. */
interface WorkoutExporter {
    /** @return false when the record was already exported. @throws HealthException */
    suspend fun export(record: WorkoutRecord): Boolean
}

/**
 * Cindy's one door to Health Connect (iOS: `HealthAccess`, `HealthExporter` and
 * `HealthMetricsReader` together): availability, the permission request, writing workouts with
 * their energy, and reading the values the readiness estimate needs.
 *
 * Reads are forgiving, like on iOS: every query that fails or is refused simply drops its
 * component.
 */
class HealthGateway(
    context: Context,
    private val settings: SettingsStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : HealthMetricsSource, WorkoutExporter {
    private val appContext = context.applicationContext

    /** Days of history the personal baseline is taken over. */
    val baselineDays: Long = 60

    // Availability

    fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(appContext, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
            else -> HealthAvailability.UNAVAILABLE
        }

    val isAvailable: Boolean get() = availability() == HealthAvailability.AVAILABLE

    /** Opens the Play Store on Health Connect (install or update, Android 13 and lower). */
    fun providerUpdateIntent(): Intent = Intent(Intent.ACTION_VIEW)
        .setPackage("com.android.vending")
        .setData("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun client(): HealthConnectClient? =
        if (isAvailable) HealthConnectClient.getOrCreate(appContext, PROVIDER_PACKAGE) else null

    // Permissions

    /**
     * Everything is asked for at once, like the single HealthKit sheet: writing workouts and
     * their energy, reading the four readiness inputs, and — where Health Connect supports it —
     * reading in the background (the periodic reminder refresh) and beyond the last 30 days (the
     * 60-day baseline). The user can refuse each one individually.
     */
    suspend fun requestedPermissions(): Set<String> {
        val client = client() ?: return WRITE_PERMISSIONS + READ_PERMISSIONS
        val optional = buildSet {
            if (client.featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
            if (client.featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
            }
        }
        return WRITE_PERMISSIONS + READ_PERMISSIONS + optional
    }

    /** Launch with `rememberLauncherForActivityResult(gateway.permissionContract())` and [requestedPermissions]. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract(PROVIDER_PACKAGE)

    suspend fun grantedPermissions(): Set<String> = try {
        client()?.permissionController?.getGrantedPermissions().orEmpty()
    } catch (_: Exception) {
        emptySet()
    }

    /** Whether workouts may be written (iOS `canWriteWorkouts`). */
    suspend fun canWriteWorkouts(): Boolean =
        grantedPermissions().contains(HealthPermission.getWritePermission(ExerciseSessionRecord::class))

    /** Whether the periodic worker may read health data while the app is not visible. */
    suspend fun canReadInBackground(): Boolean {
        val client = client() ?: return false
        return client.featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) &&
            grantedPermissions().contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    // Export

    /**
     * Writes one record as a calisthenics session with a segment per round, plus its active
     * energy. Returns false when the record was already exported.
     *
     * A workout without duration (stopped during the countdown) has nothing Health Connect could
     * store; it is marked as exported so the backfill does not keep offering it.
     *
     * @throws HealthException
     */
    override suspend fun export(record: WorkoutRecord): Boolean {
        val client = client() ?: throw HealthException(HealthError.UNAVAILABLE)
        if (hasExported(record.id)) return false
        if (!canWriteWorkouts()) throw HealthException(HealthError.DENIED)

        val sample = WorkoutHealthSample.from(record, bodyMassKilograms())
        if (!HealthRecordMapping.isExportable(sample)) {
            settings.markExportedToHealth(record.id)
            return false
        }
        val zone = zone()
        val records = buildList<Record> {
            add(HealthRecordMapping.exerciseSession(record, sample, zone))
            HealthRecordMapping.activeCalories(record, sample, zone)?.let { add(it) }
        }
        try {
            client.insertRecords(records)
        } catch (e: SecurityException) {
            throw HealthException(HealthError.DENIED, e)
        } catch (e: Exception) {
            throw HealthException(HealthError.FAILED, e)
        }
        settings.markExportedToHealth(record.id)
        return true
    }

    /** Writes every record that has not been exported yet. Returns how many were added. @throws HealthException */
    suspend fun exportMissing(history: List<WorkoutRecord>): Int {
        var exported = 0
        for (record in history) {
            if (!hasExported(record.id) && export(record)) exported += 1
        }
        return exported
    }

    fun hasExported(id: UUID): Boolean = settings.healthExportedWorkoutIds.contains(id.toString().uppercase())

    /** Records that still have to go to Health Connect — drives the backfill button. */
    fun pendingCount(history: List<WorkoutRecord>): Int = history.count { !hasExported(it.id) }

    // Reading

    override suspend fun readMetrics(now: Instant, inBackground: Boolean): HealthMetrics {
        if (!isAvailable) return HealthMetrics.none
        if (inBackground && !canReadInBackground()) return HealthMetrics.none
        return coroutineScope {
            val hrv = async {
                baseline(HeartRateVariabilityRmssdRecord::class, now) { it.time to it.heartRateVariabilityMillis }
            }
            val resting = async {
                baseline(RestingHeartRateRecord::class, now) { it.time to it.beatsPerMinute.toDouble() }
            }
            val sleep = async { sleepHours(now) }
            val bodyMass = async { bodyMassKilograms(now) }
            HealthMetrics(
                heartRateVariability = hrv.await(),
                restingHeartRate = resting.await(),
                sleepHours = sleep.await(),
                bodyMassKilograms = bodyMass.await(),
            )
        }
    }

    /** Latest body weight, also used for the energy estimate of an exported workout. */
    suspend fun bodyMassKilograms(now: Instant = Instant.now()): Double? = query {
        val client = client() ?: return@query null
        client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.before(now),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.weight?.inKilograms
    }

    /**
     * Latest daily average of a metric against the personal baseline. Heart rate variability is
     * RMSSD here (Health Connect has no SDNN); the baseline is built from RMSSD only.
     */
    private suspend fun <T : Record> baseline(
        type: KClass<T>,
        now: Instant,
        value: (T) -> Pair<Instant, Double>,
    ): MetricBaseline? = query {
        val start = now.minus(Duration.ofDays(baselineDays))
        val samples = readAll(type, TimeRangeFilter.between(start, now)).map(value)
        HealthSeries.baseline(HealthRecordMapping.dailyAverages(samples, zone()), now)
    }

    /** Time asleep last night, overlapping sources merged. */
    private suspend fun sleepHours(now: Instant): Double? = query {
        val zone = zone()
        val sessions = readAll(SleepSessionRecord::class, TimeRangeFilter.between(HealthRecordMapping.sleepWindowStart(now, zone), now))
        val parts = sessions.flatMap { session ->
            if (session.stages.isEmpty()) {
                listOf(HealthRecordMapping.SleepPart(session.startTime, session.endTime, null))
            } else {
                session.stages.map { HealthRecordMapping.SleepPart(it.startTime, it.endTime, it.stage) }
            }
        }
        val asleep = HealthRecordMapping.asleepIntervals(parts)
        if (asleep.isEmpty()) null else HealthSeries.asleepHours(asleep)
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, filter: TimeRangeFilter): List<T> {
        val client = client() ?: return emptyList()
        val records = ArrayList<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = filter, pageSize = 1000, pageToken = pageToken),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    /** A refused permission, a missing provider or a remote failure all mean "no value". */
    private suspend fun <T> query(block: suspend () -> T?): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun HealthConnectClient.featureAvailable(feature: Int): Boolean = try {
        features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (_: Exception) {
        false
    }

    companion object {
        const val PROVIDER_PACKAGE: String = "com.google.android.apps.healthdata"

        val WRITE_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        )

        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
        )
    }
}

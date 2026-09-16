package me.raddatz.cindy.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.truncate

// Small stand-ins for the Foundation / CoreGraphics types the iOS code uses.

/** CGPoint. */
data class Point(val x: Double, val y: Double)

/** CGRect. The min/mid/max accessors standardise negative sizes like CoreGraphics does. */
data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX: Double get() = minOf(x, x + width)
    val minY: Double get() = minOf(y, y + height)
    val maxX: Double get() = maxOf(x, x + width)
    val maxY: Double get() = maxOf(y, y + height)
    val midX: Double get() = (minX + maxX) / 2
    val midY: Double get() = (minY + maxY) / 2
}

/** Foundation `DateInterval`. */
data class DateInterval(val start: Instant, val end: Instant) {
    /** Length in seconds. */
    val duration: Double get() = end.secondsSince(start)
}

/** Swift `Double.rounded()`: to nearest, ties away from zero (unlike `Math.round` / `kotlin.math.round`). */
fun Double.roundedHalfAwayFromZero(): Double {
    val truncated = truncate(this)
    return if (abs(this - truncated) == 0.5) truncated + sign(this) else round(this)
}

/** Swift `Date.addingTimeInterval(_:)`. */
fun Instant.addingSeconds(seconds: Double): Instant {
    val whole = floor(seconds)
    val nanos = ((seconds - whole) * 1e9).roundToLong()
    return plusSeconds(whole.toLong()).plusNanos(nanos)
}

/** Swift `Date.timeIntervalSince(_:)`: seconds from [other] to this instant. */
fun Instant.secondsSince(other: Instant): Double {
    val duration = Duration.between(other, this)
    return duration.seconds + duration.nano / 1e9
}

/**
 * ISO-8601 like Swift's `JSONEncoder.DateEncodingStrategy.iso8601`: whole seconds in UTC
 * ("2026-09-01T10:00:00Z"); decoding accepts any offset and fractional seconds.
 */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("me.raddatz.cindy.core.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    }

    override fun deserialize(decoder: Decoder): Instant =
        OffsetDateTime.parse(decoder.decodeString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
}

/** UUIDs as upper-case strings, like Swift's `UUID` Codable conformance. */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("me.raddatz.cindy.core.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString().uppercase())
    }

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/**
 * JSON settings matching the iOS stores: defaults are written out, absent optionals are
 * omitted (Swift `encodeIfPresent`), unknown keys are ignored.
 */
@OptIn(ExperimentalSerializationApi::class)
val CindyJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

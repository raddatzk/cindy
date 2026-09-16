package me.raddatz.cindy.core.debug

import me.raddatz.cindy.core.CSVSignalReplay
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.FixtureLocator
import me.raddatz.cindy.core.Rect
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.signal.BodyPoseObservation
import me.raddatz.cindy.core.signal.FaceObservation
import me.raddatz.cindy.core.signal.FrameMetrics
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PoseJoint
import me.raddatz.cindy.core.signal.PosePoint
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.core.signal.RepThresholds
import me.raddatz.cindy.core.signal.SignalPipeline
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** No iOS counterpart: pins the CSV contract shared with iOS, `tools/analyze_csv.py` and the fixtures. */
class FrameLoggerTests {
    @Test
    fun headerMatchesTheRecordedFixtures() {
        val header = FrameLogger.HEADER.split(",")
        assertEquals(29, header.size)
        val recorded = FixtureLocator.require("recorded_squats_10_clean").readText().lineSequence().first()
        assertEquals(recorded, FrameLogger.HEADER)
    }

    @Test
    fun rowsReplayThroughTheFixtureParser() {
        val directory = Files.createTempDirectory("cindy-logs").toFile()
        val logger = FrameLogger(directory, "squat", LocalDateTime.of(2026, 9, 14, 18, 5, 9))
        val pipeline = SignalPipeline(
            Exercise.SQUAT, RepThresholds(0.44f, 0.47f, RepDirection.TROUGH, baseline = 0.49f), SignalSource.BRIGHTNESS,
        )
        val pose = BodyPoseObservation(
            mapOf(
                PoseJoint.LEFT_SHOULDER to PosePoint(0f, 0f, 0.7f),
                PoseJoint.RIGHT_SHOULDER to PosePoint(0.25f, 0f, 0.6f),
            ),
        )
        for (i in 0 until 3) {
            val observation = FrameObservation(
                timestamp = 100.0 + i / 30.0,
                face = FaceObservation(Rect(0.25, 0.5, 0.125, -0.25), 0.9f),
                pose = pose,
                orientation = 6,
                metrics = FrameMetrics(lumaMean = 0.5f, lumaCenter = 0.25f),
            )
            logger.log(observation, pipeline.process(observation), "workout,active")
        }
        logger.close()
        assertEquals(3, logger.rowCount)
        assertEquals("cindy_2026-09-14_18-05-09_squat.csv", logger.file.name)

        val lines = logger.file.readLines()
        assertEquals(FrameLogger.HEADER, lines[0])
        assertEquals(
            "0.0667,squat,brightness,0.500000,0.500000,1.000000,0.250000,0.250000,0.125000,0.250000,0.900000," +
                "0.031250,6,,0.000000,,0.700000,,rest,0,0,workout;active,0.440000,0.470000,0.250000,0.600000,," +
                "0.500000,0.250000",
            lines[3],
        )
        val frames = CSVSignalReplay.observations(logger.file.toURI().toURL())
        assertEquals(3, frames.size)
        assertEquals(0.5f, frames[2].metrics?.lumaMean)
        assertEquals(0.25f, assertNotNull(frames[2].pose?.shoulderWidth))

        // printf semantics: exact binary value, ties to even, sign kept on a rounded-away negative.
        assertEquals("0.12", FrameLogger.formatFixed(0.125, 2))
        assertEquals("0.38", FrameLogger.formatFixed(0.375, 2))
        assertEquals("0.1", FrameLogger.formatFixed(0.15, 1)) // 0.1499999999999999944…
        assertEquals("-0.000000", FrameLogger.formatFixed(-1e-9, 6))
    }
}

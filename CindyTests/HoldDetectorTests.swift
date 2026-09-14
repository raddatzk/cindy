import Foundation
import Testing
@testable import Cindy

struct HoldDetectorTests {
    let band = RepThresholds(low: 0.08, high: 0.12, direction: .peak)

    @Test func accumulatesTimeInsideBandAndCompletes() {
        let detector = HoldDetector(thresholds: band, targetSeconds: 2, config: .default)
        var completedAt: TimeInterval?
        for i in 0..<120 {
            let t = Double(i) / 30
            if case .repCompleted = detector.process(value: 0.10, confidence: 1, timestamp: t) { completedAt = t }
        }
        let done = completedAt ?? -1
        #expect(done > 2.2 && done < 2.6) // 10 arming frames + 2 s of hold
        #expect(detector.repCount == 1)
    }

    @Test func leavingBandPausesWithoutReset() {
        let detector = HoldDetector(thresholds: band, targetSeconds: 5, config: .default)
        var t: TimeInterval = 0
        func feed(_ value: Float?, frames: Int) {
            for _ in 0..<frames { _ = detector.process(value: value, confidence: value == nil ? 0 : 1, timestamp: t); t += 1 / 30 }
        }
        feed(0.10, frames: 40)          // arm + ~1 s
        let held = detector.heldSeconds
        #expect(held > 0.9 && held < 1.1)
        feed(0.30, frames: 30)          // out of band: paused
        #expect(detector.heldSeconds == held)
        feed(nil, frames: 15)           // face lost briefly: still paused, still armed
        #expect(detector.isArmed)
        feed(0.10, frames: 30)          // back: continues
        #expect(detector.heldSeconds > held + 0.8)
    }

    @Test func holdCalibrationBuildsBandAroundMean() throws {
        var trace: [CalibrationSample] = []
        for i in 0..<100 {
            trace.append(CalibrationSample(timestamp: Double(i) / 30, value: 0.10 + (i % 2 == 0 ? 0.002 : -0.002)))
        }
        let analyzer = CalibrationAnalyzer(source: .face)
        let calibration = try #require(analyzer.evaluateHold(trace))
        #expect(abs(calibration.baseline - 0.10) < 0.001)
        #expect(calibration.low < 0.08 && calibration.high > 0.12) // ±25 % of the mean at least
        #expect(analyzer.evaluateHold(Array(trace.prefix(30))) == nil) // shorter than 3 s
    }
}

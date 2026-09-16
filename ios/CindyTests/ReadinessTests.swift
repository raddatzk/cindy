import Foundation
import Testing
@testable import Cindy

struct ReadinessTests {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    private func record(hoursAgo: Double, duration: TimeInterval = 1200, reps: Int = 300,
                        plan: WorkoutPlan = .cindy) -> WorkoutRecord {
        WorkoutRecord(date: now.addingTimeInterval(-hoursAgo * 3600), rounds: reps / 30, extraReps: 0,
                      durationSeconds: duration, completed: duration >= plan.duration,
                      repsPerRound: 30, roundTimestamps: nil, plan: plan)
    }

    // MARK: - Scoring curves

    @Test func interpolationIsLinearBetweenPointsAndFlatOutside() {
        let points = [(0.0, 0.0), (1.0, 10.0), (2.0, 20.0)]
        #expect(ReadinessScoring.interpolate(-5, points) == 0)
        #expect(ReadinessScoring.interpolate(0.5, points) == 5)
        #expect(ReadinessScoring.interpolate(1.5, points) == 15)
        #expect(ReadinessScoring.interpolate(99, points) == 20)
    }

    @Test func recoveryRisesWithRestTaken() {
        #expect(ReadinessScoring.recovery(fraction: 0) == 10)
        #expect(ReadinessScoring.recovery(fraction: 1) == 85)
        #expect(ReadinessScoring.recovery(fraction: 0.5) < ReadinessScoring.recovery(fraction: 1))
        #expect(ReadinessScoring.recovery(fraction: 3) == 100)
    }

    @Test func aFullSessionNeedsTwoDaysAndAShortOneLess() {
        #expect(ReadinessScoring.recoveryHours(for: record(hoursAgo: 1)) == 48)
        #expect(ReadinessScoring.recoveryHours(for: record(hoursAgo: 1, duration: 600)) == 36)
    }

    @Test func heartRateBelowBaselineIsGoodAndVariabilityBelowBaselineIsBad() {
        #expect(ReadinessScoring.heartRateVariability(zScore: -2) < ReadinessScoring.heartRateVariability(zScore: 0))
        #expect(ReadinessScoring.heartRateVariability(zScore: 2) > ReadinessScoring.heartRateVariability(zScore: 0))
        #expect(ReadinessScoring.restingHeartRate(zScore: 2) < ReadinessScoring.restingHeartRate(zScore: 0))
        #expect(ReadinessScoring.restingHeartRate(zScore: -2) > ReadinessScoring.restingHeartRate(zScore: 0))
    }

    @Test func aVolumeSpikeScoresWorseThanASteadyWeek() {
        #expect(ReadinessScoring.trainingLoad(ratio: 1) > ReadinessScoring.trainingLoad(ratio: 1.8))
        #expect(ReadinessScoring.trainingLoad(ratio: 1) > ReadinessScoring.trainingLoad(ratio: 0.2))
    }

    // MARK: - Baseline

    @Test func baselineNeedsEnoughDaysAndSomeVariation() {
        #expect(MetricBaseline(current: 50, earlierDailyValues: Array(repeating: 45.0, count: 5)) == nil)
        let flat = MetricBaseline(current: 50, earlierDailyValues: Array(repeating: 45.0, count: 20))
        #expect(flat?.zScore == nil) // no spread: a z-score would be meaningless
        let varied = MetricBaseline(current: 60, earlierDailyValues: (0..<20).map { 40 + Double($0 % 5) * 5 })
        #expect((varied?.zScore ?? 0) > 0)
        #expect((varied?.relativeDeviation ?? 0) > 0)
    }

    @Test func zScoreIsClampedToThreeSigma() {
        let extreme = MetricBaseline(value: 1000, mean: 50, standardDeviation: 5)
        #expect(extreme.zScore == 3)
    }

    // MARK: - Series helpers

    @Test func staleSeriesYieldsNoBaseline() {
        let fresh = (0..<20).map {
            HealthSeries.DailyValue(date: now.addingTimeInterval(Double(-$0) * 86400),
                                    value: 40 + Double($0 % 4))
        }
        #expect(HealthSeries.baseline(from: fresh, now: now) != nil)
        let stale = fresh.map {
            HealthSeries.DailyValue(date: $0.date.addingTimeInterval(-5 * 86400), value: $0.value)
        }
        #expect(HealthSeries.baseline(from: stale, now: now) == nil)
    }

    @Test func overlappingSleepFromTwoSourcesIsCountedOnce() {
        let start = now.addingTimeInterval(-8 * 3600)
        let watch = DateInterval(start: start, end: start.addingTimeInterval(6 * 3600))
        let phone = DateInterval(start: start.addingTimeInterval(3 * 3600), end: start.addingTimeInterval(7 * 3600))
        #expect(HealthSeries.asleepHours([watch, phone]) == 7)
        let gap = DateInterval(start: start.addingTimeInterval(8 * 3600), end: start.addingTimeInterval(9 * 3600))
        #expect(HealthSeries.asleepHours([watch, phone, gap]) == 8)
    }

    // MARK: - Estimator

    @Test func withoutAnyDataThereIsNoEstimate() {
        #expect(ReadinessEstimator(now: now).estimate(history: []) == nil)
    }

    @Test func historyAloneCarriesTheEstimate() {
        let estimator = ReadinessEstimator(now: now)
        let rested = estimator.estimate(history: [record(hoursAgo: 48)])
        #expect(rested?.usesHealthData == false)
        #expect(rested?.score == 85)

        let justFinished = estimator.estimate(history: [record(hoursAgo: 0)])
        #expect(justFinished?.band == .rest)
    }

    @Test func aVolumeSpikePullsTheScoreDown() {
        let estimator = ReadinessEstimator(now: now)
        let spike = [record(hoursAgo: 48), record(hoursAgo: 60), record(hoursAgo: 72)]
        let spread = [record(hoursAgo: 48), record(hoursAgo: 24 * 12), record(hoursAgo: 24 * 20)]
        let spiked = estimator.estimate(history: spike)
        let steady = estimator.estimate(history: spread)
        #expect(ReadinessEstimator.loadRatio(history: spike, now: now) == 4)
        #expect((spiked?.score ?? 0) < (steady?.score ?? 0))
    }

    @Test func loadRatioNeedsThreeSessions() {
        #expect(ReadinessEstimator.loadRatio(history: [record(hoursAgo: 24), record(hoursAgo: 48)], now: now) == nil)
    }

    @Test func healthDataMovesTheScoreAndIsMarkedAsUsed() {
        let estimator = ReadinessEstimator(now: now)
        let history = [record(hoursAgo: 48)]
        let bad = HealthMetrics(heartRateVariability: MetricBaseline(value: 30, mean: 50, standardDeviation: 8),
                                restingHeartRate: MetricBaseline(value: 62, mean: 52, standardDeviation: 4),
                                sleepHours: 4.5, bodyMassKilograms: 80)
        let good = HealthMetrics(heartRateVariability: MetricBaseline(value: 70, mean: 50, standardDeviation: 8),
                                 restingHeartRate: MetricBaseline(value: 48, mean: 52, standardDeviation: 4),
                                 sleepHours: 8, bodyMassKilograms: 80)
        let poor = estimator.estimate(history: history, metrics: bad)
        let strong = estimator.estimate(history: history, metrics: good)
        #expect(poor?.usesHealthData == true)
        #expect((poor?.score ?? 100) < 60)
        #expect((strong?.score ?? 0) > (poor?.score ?? 100))
        #expect(strong?.components.count == 4) // recovery, sleep, HRV, resting pulse
    }

    @Test func theStrongestDeviationIsNamedFirst() {
        let metrics = HealthMetrics(heartRateVariability: nil, restingHeartRate: nil,
                                    sleepHours: 3, bodyMassKilograms: nil)
        let readiness = ReadinessEstimator(now: now).estimate(history: [record(hoursAgo: 48)], metrics: metrics)
        #expect(readiness?.reasons().first?.kind == .sleep)
        #expect(readiness?.reasons().count == 2)
    }

    @Test func bandsFollowTheScore() {
        func band(_ score: Double) -> Readiness.Band? {
            Readiness(components: [.init(kind: .sleep, score: score, weight: 1, detail: "")],
                      usesHealthData: false)?.band
        }
        #expect(band(20) == .rest)
        #expect(band(50) == .easy)
        #expect(band(70) == .ready)
        #expect(band(90) == .primed)
    }
}

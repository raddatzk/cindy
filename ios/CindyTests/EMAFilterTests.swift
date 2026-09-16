import Testing
@testable import Cindy

struct EMAFilterTests {
    @Test func firstSamplePassesThrough() {
        var filter = EMAFilter(alpha: 0.3)
        #expect(filter.update(10) == 10)
    }

    @Test func convergesTowardsInput() {
        var filter = EMAFilter(alpha: 0.3)
        filter.update(0)
        var last: Float = 0
        for _ in 0..<50 { last = filter.update(1) }
        #expect(last > 0.99)
    }

    @Test func alphaOneIsIdentity() {
        var filter = EMAFilter(alpha: 1)
        filter.update(5)
        #expect(filter.update(7) == 7)
    }

    @Test func referenceFrameIntervalMatchesThePerSampleFilter() {
        var perSample = EMAFilter(alpha: 0.3)
        var exact = EMAFilter(alpha: 0.3)
        var jittered = EMAFilter(alpha: 0.3)
        for i in 0..<90 {
            let x: Float = i % 30 < 15 ? 0.05 : 0.20
            let expected = perSample.update(x)
            #expect(abs(expected - exact.update(x, frameInterval: i == 0 ? nil : 1.0 / 30)) < 1e-6, "frame \(i)")
            // Recorded iPhone timestamps lie 0.0333 or 0.0334 s apart.
            let interval = i % 3 == 0 ? 0.0334 : 0.0333
            #expect(abs(expected - jittered.update(x, frameInterval: i == 0 ? nil : interval)) < 1e-3,
                    "jittered frame \(i)")
        }
    }

    @Test func oneStepAtFifteenFpsEqualsTwoStepsAtThirty() {
        var thirty = EMAFilter(alpha: 0.3)
        thirty.update(0)
        thirty.update(1, frameInterval: 1.0 / 30)
        let twoSteps = thirty.update(1, frameInterval: 1.0 / 30)
        var fifteen = EMAFilter(alpha: 0.3)
        fifteen.update(0)
        let oneStep = fifteen.update(1, frameInterval: 2.0 / 30)
        #expect(abs(twoSteps - 0.51) < 1e-5)
        #expect(abs(oneStep - twoSteps) < 1e-5)
    }

    @Test func unusableIntervalsKeepTheAlphaAndLongOnesAreCapped() {
        #expect(FrameTiming.alpha(0.3, frameInterval: nil) == 0.3)
        #expect(FrameTiming.alpha(0.3, frameInterval: 0) == 0.3)
        #expect(FrameTiming.alpha(0.3, frameInterval: -1) == 0.3)
        #expect(FrameTiming.alpha(1, frameInterval: 0.2) == 1)
        // A camera stall moves the filter as far as `maxFrameInterval` does, not all the way.
        #expect(FrameTiming.alpha(0.3, frameInterval: FrameTiming.maxFrameInterval)
            == FrameTiming.alpha(0.3, frameInterval: 5))
        #expect(FrameTiming.alpha(0.3, frameInterval: 5) < 1)
    }

    @Test func resetForgetsState() {
        var filter = EMAFilter(alpha: 0.3)
        filter.update(5)
        filter.reset()
        #expect(filter.value == nil)
        #expect(filter.update(2) == 2)
    }
}

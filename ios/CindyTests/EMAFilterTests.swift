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

    @Test func resetForgetsState() {
        var filter = EMAFilter(alpha: 0.3)
        filter.update(5)
        filter.reset()
        #expect(filter.value == nil)
        #expect(filter.update(2) == 2)
    }
}

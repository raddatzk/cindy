import CoreVideo
import Foundation
import Testing
@testable import Cindy

struct DepthMetricsTests {
    /// Depth map of `far` metres with a `near` block in the top-left cell and a column of holes.
    private func depthBuffer(width: Int = 90, height: Int = 60, far: Float, near: Float) throws -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_DepthFloat32, nil, &buffer)
        let pixelBuffer = try #require(buffer)
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
        let base = try #require(CVPixelBufferGetBaseAddress(pixelBuffer))
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        for y in 0..<height {
            let row = (base + y * bytesPerRow).assumingMemoryBound(to: Float32.self)
            for x in 0..<width {
                if x == width - 1 {
                    row[x] = .nan
                } else if x < width / 3 && y < height / 3 {
                    row[x] = near
                } else {
                    row[x] = far
                }
            }
        }
        return pixelBuffer
    }

    @Test func nearestPercentileFindsTheCloseBlockAndHolesAreCounted() throws {
        let metrics = try #require(DepthMetricsCalculator.metrics(of: depthBuffer(far: 2, near: 0.5)))
        // One of 90 columns is NaN.
        #expect(abs(metrics.validFraction - 89 / 90) < 1e-4)
        // The near block covers a ninth of the map, so the 5th and 10th percentiles land in it.
        #expect(metrics.p05 == 0.5)
        #expect(metrics.p10 == 0.5)
        #expect(metrics.median == 2)
        #expect(metrics.centerMedian == 2)
        #expect(metrics.grid.count == 9)
        #expect(metrics.grid[0] == 0.5)
        #expect(metrics.grid[4] == 2)
    }

    @Test func otherPixelFormatsAreRejected() throws {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, 8, 8, kCVPixelFormatType_DisparityFloat32, nil, &buffer)
        #expect(DepthMetricsCalculator.metrics(of: try #require(buffer)) == nil)
    }

    @Test func csvRowsHaveAFieldPerHeaderColumnWithAndWithoutDepth() {
        let columns = FrameLogger.header.split(separator: ",", omittingEmptySubsequences: false).count
        var observation = FrameObservation(timestamp: 10)
        var first: TimeInterval?
        let plain = FrameLogger.row(observation: observation, output: nil, state: "debug", firstTimestamp: &first)
        #expect(plain.split(separator: ",", omittingEmptySubsequences: false).count == columns)

        observation.depth = DepthMetrics(validFraction: 0.5, p05: 0.4, p10: 0.45, median: 1.2, centerMedian: 1.1,
                                         grid: Array(repeating: 1, count: 9), age: 0.02)
        let row = FrameLogger.row(observation: observation, output: nil, state: "debug", firstTimestamp: &first)
        let fields = row.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        let header = FrameLogger.header.split(separator: ",").map(String.init)
        #expect(fields.count == columns)
        #expect(fields[header.firstIndex(of: "depth_p10")!] == "0.450000")
        #expect(fields[header.firstIndex(of: "depth_age")!] == "0.0200")
    }
}

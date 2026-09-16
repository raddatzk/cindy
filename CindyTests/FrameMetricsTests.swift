import CoreVideo
import Foundation
import Testing
@testable import Cindy

struct FrameMetricsTests {
    /// Full-range 4:2:0 buffer whose Y plane is `outer`, with the central half set to `inner`.
    private func lumaBuffer(width: Int = 64, height: Int = 48, outer: UInt8, inner: UInt8) throws -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                            nil, &buffer)
        let pixelBuffer = try #require(buffer)
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
        let base = try #require(CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)).assumingMemoryBound(to: UInt8.self)
        let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        for y in 0..<height {
            for x in 0..<width {
                let central = y >= height / 4 && y < height * 3 / 4 && x >= width / 4 && x < width * 3 / 4
                base[y * bytesPerRow + x] = central ? inner : outer
            }
        }
        return pixelBuffer
    }

    @Test func lumaSeparatesFrameAndCentre() throws {
        let buffer = try lumaBuffer(outer: 255, inner: 0)
        let luma = try #require(FrameMetricsCalculator.luma(of: buffer))
        #expect(luma.center == 0)
        // The central half covers a quarter of the sampled pixels.
        #expect(abs(luma.mean - 0.75) < 0.01)
    }

    @Test func shoulderWidthIsTheDistanceBetweenShoulders() {
        let pose = BodyPoseObservation(joints: [
            .leftShoulder: PosePoint(x: 0.2, y: 0.5, confidence: 0.9),
            .rightShoulder: PosePoint(x: 0.5, y: 0.9, confidence: 0.9),
        ])
        #expect(abs((pose.shoulderWidth ?? 0) - 0.5) < 1e-6)
        #expect(BodyPoseObservation(joints: [.nose: PosePoint(x: 0.5, y: 0.5, confidence: 1)]).shoulderWidth == nil)
    }
}

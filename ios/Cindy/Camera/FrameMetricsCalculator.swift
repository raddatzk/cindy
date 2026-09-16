import CoreMedia
import CoreVideo
import Foundation

/// Measures the `FrameMetrics` of a camera frame.
enum FrameMetricsCalculator {
    /// Every n-th luma pixel in both directions is sampled.
    private static let lumaStep = 8

    static func measure(_ sampleBuffer: CMSampleBuffer) -> FrameMetrics {
        var metrics = FrameMetrics()
        if let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer), let luma = luma(of: pixelBuffer) {
            metrics.lumaMean = luma.mean
            metrics.lumaCenter = luma.center
        }
        return metrics
    }

    /// Mean of the Y plane (full-range 4:2:0) over the whole frame and its central half, 0…1.
    static func luma(of pixelBuffer: CVPixelBuffer) -> (mean: Float, center: Float)? {
        guard CVPixelBufferIsPlanar(pixelBuffer), CVPixelBufferGetPlaneCount(pixelBuffer) > 0 else { return nil }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return nil }
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let pixels = base.assumingMemoryBound(to: UInt8.self)

        var total = 0, count = 0, centerTotal = 0, centerCount = 0
        for y in stride(from: 0, to: height, by: lumaStep) {
            let row = pixels + y * bytesPerRow
            let centerRow = y >= height / 4 && y < height * 3 / 4
            for x in stride(from: 0, to: width, by: lumaStep) {
                let value = Int(row[x])
                total += value
                count += 1
                if centerRow && x >= width / 4 && x < width * 3 / 4 {
                    centerTotal += value
                    centerCount += 1
                }
            }
        }
        guard count > 0, centerCount > 0 else { return nil }
        return (Float(total) / Float(count) / 255, Float(centerTotal) / Float(centerCount) / 255)
    }
}

import AVFoundation
import CoreVideo
import Foundation

/// Measures the `DepthMetrics` of a TrueDepth depth map.
enum DepthMetricsCalculator {
    /// The map is sampled on roughly this many columns, whatever its resolution.
    private static let sampledColumns = 160

    static func measure(_ depthData: AVDepthData) -> DepthMetrics? {
        let depth = depthData.depthDataType == kCVPixelFormatType_DepthFloat32
            ? depthData
            : depthData.converting(toDepthDataType: kCVPixelFormatType_DepthFloat32)
        return metrics(of: depth.depthDataMap)
    }

    /// Percentiles of a `kCVPixelFormatType_DepthFloat32` map; NaN, infinite and zero depths count as holes.
    static func metrics(of pixelBuffer: CVPixelBuffer) -> DepthMetrics? {
        guard CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_DepthFloat32 else { return nil }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let step = max(1, width / sampledColumns)

        var all: [Float] = []
        var center: [Float] = []
        var cells = Array(repeating: [Float](), count: 9)
        all.reserveCapacity((width / step + 1) * (height / step + 1))
        var sampled = 0
        for y in stride(from: 0, to: height, by: step) {
            let row = (base + y * bytesPerRow).assumingMemoryBound(to: Float32.self)
            let centerRow = y >= height / 4 && y < height * 3 / 4
            let cellRow = min(2, y * 3 / height)
            for x in stride(from: 0, to: width, by: step) {
                sampled += 1
                let value = row[x]
                guard value.isFinite, value > 0 else { continue }
                all.append(value)
                if centerRow && x >= width / 4 && x < width * 3 / 4 {
                    center.append(value)
                }
                cells[cellRow * 3 + min(2, x * 3 / width)].append(value)
            }
        }
        guard sampled > 0 else { return nil }
        all.sort()
        return DepthMetrics(
            validFraction: Float(all.count) / Float(sampled),
            p05: percentile(all, 0.05),
            p10: percentile(all, 0.10),
            median: percentile(all, 0.5),
            centerMedian: percentile(center.sorted(), 0.5),
            grid: cells.map { percentile($0.sorted(), 0.5) }
        )
    }

    /// Nearest-rank percentile of an ascending array.
    private static func percentile(_ sorted: [Float], _ fraction: Float) -> Float? {
        guard !sorted.isEmpty else { return nil }
        let index = Int((Float(sorted.count - 1) * fraction).rounded())
        return sorted[index]
    }
}

import Foundation

/// Per-frame CSV logger for the debug/recording mode.
///
/// Columns are plain numbers only (no images). One row per camera frame:
/// timestamp, exercise, source, raw & smoothed signal, confidence, face box,
/// selected pose landmarks, detector event/phase, rep count and app state.
final class FrameLogger {
    static let header = [
        "t", "exercise", "source", "raw", "smoothed", "confidence",
        "face_x", "face_y", "face_w", "face_h", "face_conf", "face_area", "orientation",
        "pose_nose_y", "pose_shoulder_y", "pose_hip_y", "pose_conf",
        "event", "phase", "armed", "rep_count", "state"
    ].joined(separator: ",")

    let url: URL
    private let queue = DispatchQueue(label: "me.raddatz.cindy.logger", qos: .utility)
    private var handle: FileHandle?
    private var buffer = Data()
    private(set) var rowCount = 0
    private var firstTimestamp: TimeInterval?

    static var logsDirectory: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("DebugLogs", isDirectory: true)
    }

    /// Creates a new CSV file named after the current date and the exercise.
    init(label: String) throws {
        let directory = FrameLogger.logsDirectory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        let name = "cindy_\(formatter.string(from: Date()))_\(label).csv"
        url = directory.appendingPathComponent(name)
        try (FrameLogger.header + "\n").write(to: url, atomically: true, encoding: .utf8)
        handle = try FileHandle(forWritingTo: url)
        handle?.seekToEndOfFile()
    }

    func log(observation: FrameObservation, output: PipelineOutput?, state: String) {
        let row = FrameLogger.row(observation: observation, output: output, state: state,
                                  firstTimestamp: &firstTimestamp)
        queue.async {
            self.buffer.append(contentsOf: Array((row + "\n").utf8))
            self.rowCount += 1
            if self.buffer.count > 16 * 1024 {
                self.flushLocked()
            }
        }
    }

    func flush() {
        queue.sync { flushLocked() }
    }

    func close() {
        queue.sync {
            flushLocked()
            try? handle?.close()
            handle = nil
        }
    }

    private func flushLocked() {
        guard !buffer.isEmpty, let handle else { return }
        handle.write(buffer)
        buffer.removeAll(keepingCapacity: true)
    }

    // MARK: - Row formatting

    static func row(observation: FrameObservation, output: PipelineOutput?, state: String,
                    firstTimestamp: inout TimeInterval?) -> String {
        if firstTimestamp == nil { firstTimestamp = observation.timestamp }
        let t = observation.timestamp - (firstTimestamp ?? observation.timestamp)
        var fields: [String] = []
        fields.append(format(t, 4))
        fields.append(output?.exercise.rawValue ?? "")
        fields.append(output?.source.rawValue ?? "")
        fields.append(format(output?.raw))
        fields.append(format(output?.smoothed))
        fields.append(format(output?.confidence ?? observation.face?.confidence ?? 0))
        if let face = observation.face {
            fields.append(format(Float(face.boundingBox.minX)))
            fields.append(format(Float(face.boundingBox.minY)))
            fields.append(format(Float(face.boundingBox.width)))
            fields.append(format(Float(face.boundingBox.height)))
            fields.append(format(face.confidence))
            fields.append(format(face.area))
        } else {
            fields.append(contentsOf: Array(repeating: "", count: 6))
        }
        fields.append(observation.orientation.map { String($0.rawValue) } ?? "")
        fields.append(format(observation.pose?.noseY))
        fields.append(format(observation.pose?.shoulderY))
        fields.append(format(observation.pose?.hipY))
        fields.append(format(observation.pose?.overallConfidence))
        fields.append(eventName(output?.event))
        fields.append(output?.phase.rawValue ?? "")
        fields.append(output.map { $0.isArmed ? "1" : "0" } ?? "")
        fields.append(output.map { String($0.repCount) } ?? "")
        fields.append(state.replacingOccurrences(of: ",", with: ";"))
        return fields.joined(separator: ",")
    }

    static func eventName(_ event: RepDetectorEvent?) -> String {
        switch event {
        case nil: return ""
        case .armed: return "armed"
        case .disarmed: return "disarmed"
        case .repCompleted: return "rep"
        case .repRejected: return "rejected"
        }
    }

    private static func format(_ value: Float?, _ digits: Int = 6) -> String {
        guard let value else { return "" }
        return String(format: "%.\(digits)f", value)
    }

    private static func format(_ value: Double, _ digits: Int) -> String {
        String(format: "%.\(digits)f", value)
    }
}

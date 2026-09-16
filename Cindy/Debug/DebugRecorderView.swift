import SwiftUI

/// Hidden developer mode: live signal values, sparkline with thresholds and
/// CSV recording of every frame. Reached via a long press on the title.
struct DebugRecorderView: View {
    @Environment(AppModel.self) private var model
    @State private var engine: DebugEngine?

    var body: some View {
        Group {
            if let engine {
                DebugScreen(engine: engine)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Debug / Aufnahme")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if engine == nil {
                let engine = DebugEngine(profile: model.calibration, config: model.config)
                self.engine = engine
                Task { await engine.start() }
            }
        }
        .onDisappear { engine?.stop() }
    }
}

private struct DebugScreen: View {
    @Bindable var engine: DebugEngine

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let message = engine.errorMessage {
                    Text(message).foregroundStyle(.red)
                }
                pickers
                HStack(alignment: .top, spacing: 12) {
                    CameraPreviewView(session: engine.camera.session)
                        .frame(width: 120, height: 160)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    values
                }
                SignalSparkline(values: engine.history, thresholds: engine.activeThresholds)
                    .frame(height: 140)
                thresholdInfo
                recording
                logs
            }
            .padding()
        }
    }

    private var pickers: some View {
        VStack(spacing: 8) {
            Picker("Übung", selection: $engine.exercise) {
                ForEach(Exercise.allCases) { Text($0.displayName).tag($0) }
            }
            .pickerStyle(.segmented)
            Picker("Signal", selection: $engine.source) {
                ForEach(SignalSource.allCases) { Text($0.displayName).tag($0) }
            }
            .pickerStyle(.segmented)
            Toggle("Body Pose mitlaufen lassen (CPU!)", isOn: $engine.bodyPose)
                .font(.subheadline)
        }
    }

    private var values: some View {
        VStack(alignment: .leading, spacing: 3) {
            let output = engine.latest
            let face = engine.latestObservation?.face
            row("raw", fmt(output?.raw))
            row("ema", fmt(output?.smoothed))
            row("conf", fmt(output?.confidence))
            row("face", face.map { String(format: "%.3f × %.3f", $0.boundingBox.width, $0.boundingBox.height) } ?? "–")
            row("y", fmt(face?.centerY))
            row("orient", engine.latestObservation?.orientation.map { "\($0.rawValue)" } ?? "–")
            row("phase", output?.phase.rawValue ?? "–")
            row("armed", output.map { $0.isArmed ? "ja" : "nein" } ?? "–")
            row("reps", "\(engine.repCount)")
            if let pose = engine.latestObservation?.pose {
                row("nose y", fmt(pose.noseY))
                row("shoulder y", fmt(pose.shoulderY))
                row("hip y", fmt(pose.hipY))
                row("shoulder w", fmt(pose.shoulderWidth))
            }
            if let metrics = engine.latestObservation?.metrics {
                row("luma", fmt(metrics.lumaMean))
            }
        }
        .font(.caption.monospaced())
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var thresholdInfo: some View {
        HStack {
            let thresholds = engine.activeThresholds
            Text(String(format: "low %.4f", thresholds.low)).foregroundStyle(.blue)
            Text(String(format: "high %.4f", thresholds.high)).foregroundStyle(.red)
            Text(thresholds.direction == .peak ? "peak" : "trough")
            Spacer()
            Text((engine.usesCalibration ? "kalibriert" : "hartkodiert") + (thresholds.isRelative ? ", relativ" : ""))
                .foregroundStyle(.secondary)
        }
        .font(.caption.monospaced())
    }

    private var recording: some View {
        VStack(spacing: 8) {
            Button {
                if engine.isRecording { engine.stopRecording() } else { engine.startRecording() }
            } label: {
                Label(engine.isRecording ? "Aufnahme stoppen" : "Aufnahme starten",
                      systemImage: engine.isRecording ? "stop.circle.fill" : "record.circle")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .brandProminentButtonStyle()
            .tint(engine.isRecording ? .red : .brand)
            if engine.isRecording {
                Text("\(engine.recordedRows) Frames …")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Button("Zähler zurücksetzen") { engine.resetPipeline() }
                .font(.subheadline)
        }
    }

    private var logs: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Aufnahmen")
                .font(.headline)
            if engine.logFiles.isEmpty {
                Text("Noch keine CSV-Dateien.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(engine.logFiles, id: \.self) { url in
                HStack {
                    Text(url.lastPathComponent)
                        .font(.caption.monospaced())
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    ShareLink(item: url) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    Button(role: .destructive) {
                        engine.deleteLog(url)
                    } label: {
                        Image(systemName: "trash")
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value)
        }
    }

    private func fmt(_ value: Float?) -> String {
        value.map { String(format: "%.4f", $0) } ?? "–"
    }
}

@MainActor
@Observable
final class DebugEngine {
    var exercise: Exercise = .pushUp { didSet { rebuildPipeline() } }
    var source: SignalSource = .face { didSet { rebuildPipeline() } }
    /// Runs body pose next to the face even when the face is the signal (CSV comparisons).
    var bodyPose = false { didSet { updateDetection() } }

    private(set) var latest: PipelineOutput?
    private(set) var latestObservation: FrameObservation?
    private(set) var history: [Float] = []
    private(set) var repCount = 0
    private(set) var isRecording = false
    private(set) var recordedRows = 0
    private(set) var logFiles: [URL] = []
    private(set) var errorMessage: String?

    let camera: CameraSession
    private let processor: FrameProcessor
    private let profile: CalibrationProfile?
    private let config: SignalConfig
    private var logger: FrameLogger?
    private var running = false
    private let historyLength = 300

    init(profile: CalibrationProfile?, config: SignalConfig) {
        self.profile = profile
        self.config = config
        self.camera = CameraSession(config: config)
        self.processor = FrameProcessor(camera: camera)
        processor.setMetricsEnabled(true)
        processor.onFrame = { [weak self] observation, output in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.handle(observation, output) }
            }
        }
        refreshLogs()
    }

    var usesCalibration: Bool { profile?.calibration(for: exercise) != nil }

    var thresholds: RepThresholds {
        profile?.calibration(for: exercise)?.thresholds ?? .hardcoded(for: exercise)
    }

    /// What the running detector compares against (relative thresholds are rescaled once armed).
    var activeThresholds: RepThresholds { latest?.thresholds ?? thresholds }

    func start() async {
        guard await CameraSession.requestAccess() else {
            errorMessage = CameraError.notAuthorized.localizedDescription
            return
        }
        do {
            try camera.configure()
        } catch {
            errorMessage = error.localizedDescription
            return
        }
        AudioFeedback.shared.prepare()
        camera.start()
        running = true
        UIApplication.shared.isIdleTimerDisabled = true
        rebuildPipeline()
    }

    func stop() {
        stopRecording()
        guard running else { return }
        camera.stop()
        running = false
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func resetPipeline() {
        rebuildPipeline()
    }

    func startRecording() {
        guard !isRecording else { return }
        do {
            let logger = try FrameLogger(label: "\(exercise.rawValue)_\(source.rawValue)")
            self.logger = logger
            processor.setLogger(logger, stateProvider: { "debug" })
            isRecording = true
            recordedRows = 0
        } catch {
            errorMessage = "CSV konnte nicht angelegt werden: \(error.localizedDescription)"
        }
    }

    func stopRecording() {
        guard isRecording else { return }
        processor.setLogger(nil)
        logger?.close()
        logger = nil
        isRecording = false
        refreshLogs()
    }

    func deleteLog(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
        refreshLogs()
    }

    // MARK: - Private

    private func rebuildPipeline() {
        updateDetection()
        let pipeline = SignalPipeline(exercise: exercise, thresholds: thresholds, source: source, config: config)
        processor.setPipeline(pipeline)
        history.removeAll()
        repCount = 0
    }

    /// The face always runs in the debug mode, so its drop-outs stay visible in recordings.
    private func updateDetection() {
        processor.setDetection(face: true, bodyPose: bodyPose || source != .face)
    }

    private func handle(_ observation: FrameObservation, _ output: PipelineOutput?) {
        latestObservation = observation
        latest = output
        if let output {
            repCount = output.repCount
            if let value = output.smoothed {
                history.append(value)
                if history.count > historyLength { history.removeFirst(history.count - historyLength) }
            }
            if case .repCompleted = output.event {
                AudioFeedback.shared.beep()
            }
        }
        if isRecording { recordedRows += 1 }
    }

    private func refreshLogs() {
        let directory = FrameLogger.logsDirectory
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        logFiles = files.filter { $0.pathExtension == "csv" }.sorted { $0.lastPathComponent > $1.lastPathComponent }
    }
}

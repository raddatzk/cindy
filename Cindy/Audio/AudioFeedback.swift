import AVFoundation
import Foundation

/// Beeps. Uses the `.playback` session category so feedback is audible even
/// with the ring switch on silent, and mixes with music.
@MainActor
final class AudioFeedback {
    static let shared = AudioFeedback()

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var beepBuffer: AVAudioPCMBuffer?
    private var highBeepBuffer: AVAudioPCMBuffer?
    private var isPrepared = false
    private var isGraphBuilt = false
    private var interruptionObserver: NSObjectProtocol?
    var isEnabled = true

    private init() {}

    deinit {
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
    }

    /// Builds the graph once and activates the audio session. Safe to call
    /// again: after an interruption only the activation has to be redone.
    func prepare() {
        buildGraph()
        activate()
    }

    private func buildGraph() {
        guard !isGraphBuilt else { return }
        let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        beepBuffer = AudioFeedback.makeTone(frequency: 1_000, duration: 0.08, format: format)
        highBeepBuffer = AudioFeedback.makeTone(frequency: 1_500, duration: 0.15, format: format)
        observeInterruptions()
        isGraphBuilt = true
    }

    private func activate() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers, .duckOthers])
            try session.setActive(true)
        } catch {
            // Audio is best effort.
        }
        do {
            try engine.start()
            isPrepared = true
        } catch {
            isPrepared = false
        }
    }

    // MARK: - Interruptions

    /// A call deactivates the audio session and stops the engine, and nothing
    /// brings either back on its own. The phone is on the floor during a
    /// workout, so the beeps are the whole feedback channel — silence for the
    /// rest of the session is not something the athlete would notice in time.
    private func observeInterruptions() {
        guard interruptionObserver == nil else { return }
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            MainActor.assumeIsolated { self?.handleInterruption(note) }
        }
    }

    private func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            // The engine is already stopped; scheduling into it would be silent
            // anyway, and `play` skips it while this is false.
            isPrepared = false
        case .ended:
            // Deliberately not gated on `.shouldResume`: these are short
            // feedback tones over a mixing session, not media playback that
            // would rudely take the stage back. If the system refuses, the
            // `setActive` throw is caught and the next `prepare` tries again.
            activate()
        @unknown default:
            break
        }
    }

    /// Short tick for every counted rep.
    func beep() {
        play(beepBuffer)
    }

    /// Single higher tone: "go", and the last rep of an exercise.
    func goSignal() {
        play(highBeepBuffer)
    }

    /// Three tones: workout or calibration finished.
    func endSignal() {
        guard let highBeepBuffer else { return }
        for _ in 0..<3 {
            play(highBeepBuffer)
        }
    }

    func stop() {
        player.stop()
    }

    // MARK: - Private

    private func play(_ buffer: AVAudioPCMBuffer?) {
        guard isEnabled, isPrepared, let buffer else { return }
        if !engine.isRunning {
            try? engine.start()
        }
        if !player.isPlaying {
            player.play()
        }
        player.scheduleBuffer(buffer, at: nil, options: [], completionHandler: nil)
    }

    private static func makeTone(frequency: Double, duration: Double, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let sampleRate = format.sampleRate
        let frameCount = AVAudioFrameCount(duration * sampleRate)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else { return nil }
        buffer.frameLength = frameCount
        guard let samples = buffer.floatChannelData?[0] else { return nil }
        let fade = Int(0.005 * sampleRate)
        for i in 0..<Int(frameCount) {
            let t = Double(i) / sampleRate
            var amplitude = 0.8
            if i < fade { amplitude *= Double(i) / Double(fade) }
            if i > Int(frameCount) - fade { amplitude *= Double(Int(frameCount) - i) / Double(fade) }
            samples[i] = Float(amplitude * sin(2 * .pi * frequency * t))
        }
        return buffer
    }
}

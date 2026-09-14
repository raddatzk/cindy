import AVFoundation
import Foundation

/// Beeps and speech in the app language. Uses the `.playback` session category
/// so feedback is audible even with the ring switch on silent, and mixes with music.
@MainActor
final class AudioFeedback {
    static let shared = AudioFeedback()

    private let synthesizer = AVSpeechSynthesizer()
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var beepBuffer: AVAudioPCMBuffer?
    private var highBeepBuffer: AVAudioPCMBuffer?
    private var isPrepared = false
    var isEnabled = true

    private init() {}

    func prepare() {
        guard !isPrepared else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers, .duckOthers])
            try session.setActive(true)
        } catch {
            // Audio is best effort.
        }
        let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        beepBuffer = AudioFeedback.makeTone(frequency: 1_000, duration: 0.08, format: format)
        highBeepBuffer = AudioFeedback.makeTone(frequency: 1_500, duration: 0.15, format: format)
        do {
            try engine.start()
            isPrepared = true
        } catch {
            isPrepared = false
        }
    }

    /// Short tick for every counted rep.
    func beep() {
        play(beepBuffer)
    }

    /// Single higher tone: "go".
    func goSignal() {
        play(highBeepBuffer)
    }

    /// Three tones: workout finished.
    func endSignal() {
        guard let highBeepBuffer else { return }
        for _ in 0..<3 {
            play(highBeepBuffer)
        }
    }

    func speak(_ text: String) {
        guard isEnabled else { return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: Localization.speechLanguage)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        utterance.volume = 1
        synthesizer.stopSpeaking(at: .word)
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
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

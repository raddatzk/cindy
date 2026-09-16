import Foundation

/// Plausibility check for brightness reps: a dip in brightness only counts when a body
/// signal moved in the same cycle, so a cloud or a lamp cannot count a squat.
///
/// Evidence is any of: the face area grew by `evidenceFaceAreaRatio` over its rest level
/// (looking down), the shoulder width grew by `evidenceShoulderRatio`, or face or pose were
/// seen and then vanished for `evidenceLostFrames` consecutive frames (looking ahead, the
/// floor camera loses both at the bottom of the squat).
struct BodyEvidence: Sendable {
    let config: SignalConfig

    /// Whether any observation was fed; replays of a bare scalar have none and skip the check.
    private(set) var hasObservations = false
    private var restFaceArea: Float?
    private var restShoulderWidth: Float?

    private var inCycle = false
    private var cycleRestFaceArea: Float?
    private var cycleRestShoulderWidth: Float?
    private var maxFaceArea: Float = 0
    private var maxShoulderWidth: Float = 0
    private var face = Presence()
    private var pose = Presence()

    /// Seen-then-missing bookkeeping for one detector.
    private struct Presence: Sendable {
        var seen = false
        var missingRun = 0
        var longestMissingRun = 0

        mutating func update(present: Bool) {
            if present {
                seen = true
                missingRun = 0
            } else if seen {
                missingRun += 1
                longestMissingRun = max(longestMissingRun, missingRun)
            }
        }
    }

    private let restAlpha: Float = 0.1

    init(config: SignalConfig = .default) {
        self.config = config
    }

    /// Feeds one frame; `cycleActive` is whether the rep detector is inside a cycle.
    mutating func update(_ observation: FrameObservation, cycleActive: Bool) {
        hasObservations = true
        let faceArea = observation.face?.area
        let shoulders = observation.pose?.shoulderWidthSample
        let shoulderWidth = shoulders.flatMap { $0.confidence >= config.evidencePoseMinConfidence ? $0.value : nil }

        guard cycleActive else {
            inCycle = false
            if let faceArea { restFaceArea = restFaceArea.map { $0 + restAlpha * (faceArea - $0) } ?? faceArea }
            if let shoulderWidth {
                restShoulderWidth = restShoulderWidth.map { $0 + restAlpha * (shoulderWidth - $0) } ?? shoulderWidth
            }
            return
        }
        if !inCycle {
            inCycle = true
            cycleRestFaceArea = restFaceArea
            cycleRestShoulderWidth = restShoulderWidth
            maxFaceArea = 0
            maxShoulderWidth = 0
            face = Presence(seen: restFaceArea != nil)
            pose = Presence(seen: restShoulderWidth != nil)
        }
        if let faceArea { maxFaceArea = max(maxFaceArea, faceArea) }
        if let shoulderWidth { maxShoulderWidth = max(maxShoulderWidth, shoulderWidth) }
        face.update(present: faceArea != nil)
        pose.update(present: shoulderWidth != nil)
    }

    /// Whether the current cycle showed a body moving.
    var supportsCycle: Bool {
        if let rest = cycleRestFaceArea, rest > 0, maxFaceArea >= config.evidenceFaceAreaRatio * rest { return true }
        if let rest = cycleRestShoulderWidth, rest > 0, maxShoulderWidth >= config.evidenceShoulderRatio * rest {
            return true
        }
        let lostFrames = max(config.evidenceLostFrames, 1)
        return face.longestMissingRun >= lostFrames || pose.longestMissingRun >= lostFrames
    }
}

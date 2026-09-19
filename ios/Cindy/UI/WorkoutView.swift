import SwiftUI

struct WorkoutView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var engine: WorkoutEngine?
    @State private var showPreview = false
    @State private var confirmAbort = false
    var onFinish: (WorkoutRecord) -> Void

    var body: some View {
        Group {
            if let engine {
                WorkoutScreen(engine: engine, showPreview: $showPreview, confirmAbort: $confirmAbort)
                    .onChange(of: engine.result) { _, record in
                        if let record { onFinish(record) }
                    }
            } else {
                ProgressView()
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            guard engine == nil, let profile = model.calibration else {
                if model.calibration == nil { dismiss() }
                return
            }
            let engine = WorkoutEngine(profile: profile, plan: model.plan, config: model.config,
                                       logToCSV: model.recordWorkouts)
            self.engine = engine
            Task { await engine.start() }
        }
        .onDisappear {
            engine?.abort()
        }
    }
}

private struct WorkoutScreen: View {
    @Bindable var engine: WorkoutEngine
    @Binding var showPreview: Bool
    @Binding var confirmAbort: Bool
    @State private var editingPlan = false
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOverEnabled

    var body: some View {
        ZStack {
            Color.screenBackground.ignoresSafeArea()
            // The big numbers shrink to fit on their own; the controls cannot.
            // At the largest text sizes the screen scrolls rather than pushing
            // Pause and Stop off the bottom.
            ViewThatFits(in: .vertical) {
                layout
                ScrollView { layout }
            }
            .animation(reduceMotion ? nil : .snappy, value: engine.phase)
            .animation(reduceMotion ? nil : .snappy, value: engine.isPaused)
            .animation(reduceMotion ? nil : .snappy, value: engine.resumeCountdown)

            if engine.phase == .countdown {
                countdownOverlay
            }
            if engine.isPaused {
                pauseOverlay
            }
            if let message = engine.errorMessage {
                errorOverlay(message)
            }
        }
        .confirmationDialog(L("Stop workout?"), isPresented: $confirmAbort, titleVisibility: .visible) {
            Button(L("Stop and show result"), role: .destructive) { engine.abort() }
            Button(L("Keep going"), role: .cancel) {}
        }
        .sheet(isPresented: $editingPlan) {
            WorkoutPlanSheet(engine: engine) { plan in
                // Kept for the next workout as well: it is what turned out to be doable.
                if engine.updatePlan(plan) { model.plan = plan }
            }
        }
        // Reps and exercise changes only beep, and the phone is on the floor.
        // Everyone else can glance at the screen; VoiceOver users get them spoken.
        .onChange(of: engine.repCount) { previous, reps in
            guard voiceOverEnabled, reps > previous, !engine.exercise.isHold else { return }
            AccessibilityNotification.Announcement(
                L("\(reps) of \(engine.plan.target(for: engine.exercise))")).post()
        }
        .onChange(of: engine.exercise) { _, exercise in
            guard voiceOverEnabled, engine.phase != .finished else { return }
            AccessibilityNotification.Announcement(L("Now: \(exercise.displayName)")).post()
        }
    }

    private var layout: some View {
        VStack(spacing: 12) {
            topBar
            Spacer(minLength: 0)
            if engine.phase == .plank { finalScore } else { timer }
            exerciseBlock
            Spacer(minLength: 0)
            statusLine
            if showPreview {
                CameraPreviewView(session: engine.camera.session)
                    .frame(width: 120, height: 160)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            controls
        }
        .padding()
    }

    private var topBar: some View {
        HStack {
            Text(L("Round \(engine.currentRound)"))
                .font(.title2.bold())
            Spacer()
            if engine.isLogging {
                Label(L("REC"), systemImage: "record.circle")
                    .font(.caption.bold())
                    .foregroundStyle(.red)
            }
            Text(engine.score.notation)
                .font(.title3.monospacedDigit())
                .foregroundStyle(.secondary)
            Button {
                showPreview.toggle()
            } label: {
                Image(systemName: showPreview ? "video.fill" : "video.slash")
            }
            .accessibilityLabel(showPreview ? L("Hide camera preview") : L("Show camera preview"))
            .padding(.leading, 8)
        }
    }

    private var timer: some View {
        VStack(spacing: 0) {
            Text(WorkoutScreen.format(engine.remaining))
                .font(.system(size: 96, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(isLastMinute ? Color.red : Color.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            // Red alone says nothing to someone who cannot tell it apart.
            if isLastMinute {
                Text(L("Last minute"))
                    .font(.subheadline.weight(.semibold))
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var isLastMinute: Bool { engine.remaining <= 60 }

    /// Once the AMRAP is over the clock has nothing left to say; the score is final.
    private var finalScore: some View {
        VStack(spacing: 0) {
            Text(L("Time is up"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(engine.score.notation)
                .font(.system(size: 72, weight: .bold, design: .rounded).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
        .accessibilityElement(children: .combine)
    }

    private var exerciseBlock: some View {
        VStack(spacing: 4) {
            Text(engine.exercise.displayName)
                .font(.system(size: 40, weight: .semibold, design: .rounded))
                .foregroundStyle(.brand)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text(verbatim: engine.exercise.isHold ? "\(Int(engine.heldSeconds ?? 0))" : "\(engine.repCount)")
                    .font(.system(size: 140, weight: .black, design: .rounded).monospacedDigit())
                    .contentTransition(reduceMotion ? .identity : .numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.4)
                Text(verbatim: "/ \(engine.plan.target(for: engine.exercise))\(engine.exercise.isHold ? " " + L("unit.seconds") : "")")
                    .font(.system(size: 40, weight: .semibold, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
            // "7 / 10" read out as "seven slash ten" is noise; say what it is.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(engine.exercise.displayName)
            .accessibilityValue(L("\(engine.exercise.isHold ? Int(engine.heldSeconds ?? 0) : engine.repCount) of \(engine.plan.target(for: engine.exercise))"))
            if engine.exercise.isHold {
                ProgressView(value: min(engine.heldSeconds ?? 0, Double(engine.plan.target(for: engine.exercise))),
                             total: Double(engine.plan.target(for: engine.exercise)))
                    .tint(.brand)
                    .frame(maxWidth: 240)
                holdControl
            } else if engine.phase == .transition {
                transitionBanner
            } else if engine.plan.sets.count > 1 {
                Text(L("Up next: \(engine.nextExercise.displayName)"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// The plank runs on a timer: the athlete starts each set once in position and may end it early.
    @ViewBuilder
    private var holdControl: some View {
        if engine.plan.plankSets > 1 {
            Text(L("Set \(engine.plankSet) of \(engine.plan.plankSets)"))
                .font(.headline)
                .foregroundStyle(.secondary)
        }
        if let countdown = engine.holdCountdown {
            Text(verbatim: "\(countdown)")
                .font(.system(size: 56, weight: .black, design: .rounded))
                .foregroundStyle(.brand)
                .contentTransition(reduceMotion ? .identity : .numericText(countsDown: true))
                .accessibilityLabel(L("Starting in \(countdown)"))
        } else if !engine.isPlankRunning {
            Button {
                engine.startPlank()
            } label: {
                Label(L("Start \(engine.exercise.displayName)"), systemImage: "play.fill")
                    .font(.title2.bold())
                    .padding(.horizontal, 24)
                    .padding(.vertical, 10)
            }
            .brandProminentButtonStyle()
        } else {
            Button {
                engine.finishPlank()
            } label: {
                Label(L("Finish \(engine.exercise.displayName)"), systemImage: "checkmark")
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.bordered)
        }
    }

    /// Big visual cue while waiting for the next exercise's start position.
    private var transitionBanner: some View {
        VStack(spacing: 2) {
            Text(L("Now do"))
                .font(.subheadline.weight(.semibold))
            Text(engine.exercise.displayName)
                .font(.title.bold())
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 10)
        .background(Color.brand, in: Capsule())
        .foregroundStyle(.onBrand)
        .transition(reduceMotion ? .opacity : .scale.combined(with: .opacity))
    }

    /// The colour sits on the icons only. System green on white is 2.2:1, too
    /// faint for footnote text, and the icon and the words already change
    /// with the state, so nothing depends on seeing the colour.
    private var statusLine: some View {
        HStack(spacing: 16) {
            // Nothing is measured during the plank, so there is nobody to detect.
            if engine.phase != .plank { subjectLabel }
            Label {
                Text(statusText)
                    .foregroundStyle(.secondary)
            } icon: {
                Image(systemName: statusIcon)
                    .foregroundStyle(engine.isSignalArmed ? AnyShapeStyle(.green) : AnyShapeStyle(.secondary))
            }
        }
        .font(.footnote)
    }

    private var statusIcon: String {
        if engine.phase == .plank { return engine.isPlankRunning ? "timer" : "hand.tap" }
        return engine.isSignalArmed ? "waveform.path.ecg" : "hourglass"
    }

    private var subjectLabel: some View {
        Label {
            if engine.trackedSource == .face {
                Text(engine.subjectDetected ? L("Face") : L("No face"))
            } else {
                Text(engine.subjectDetected ? L("Person") : L("No person"))
            }
        } icon: {
            Image(systemName: engine.trackedSource == .face
                  ? (engine.subjectDetected ? "face.smiling" : "face.dashed")
                  : (engine.subjectDetected ? "figure.stand" : "person.fill.questionmark"))
                .foregroundStyle(engine.subjectDetected ? Color.green : Color.brand)
        }
    }

    private var statusText: String {
        switch engine.phase {
        case .plank:
            if engine.isPlankRunning { return L("Hold") }
            return engine.holdCountdown == nil ? L("Tap start once you are in position") : L("Get ready")
        case .transition: return L("Waiting for start position")
        case .active:
            return engine.isSignalArmed ? L("Counting") : L("Signal lost")
        case .paused: return L("Paused")
        default: return ""
        }
    }

    private var controls: some View {
        VStack(spacing: 12) {
            // The score is final during the plank, and the plank has no pause.
            if engine.phase != .plank { adjustButtons }
            HStack(spacing: 16) {
                if engine.phase != .plank { pauseButton }
                Button(role: .destructive) {
                    confirmAbort = true
                } label: {
                    Label(L("Stop"), systemImage: "xmark")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.bordered)
            }
        }
        .disabled(engine.phase == .finished)
    }

    private var adjustButtons: some View {
        HStack(spacing: 16) {
            Button {
                engine.adjust(by: -1)
            } label: {
                Text(verbatim: "−1")
                    .font(.title.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.bordered)
            // "−1" is a typographic minus, which neither VoiceOver nor
            // Voice Control reads as anything sayable.
            .accessibilityLabel(L("One rep less"))
            .accessibilityInputLabels([L("One rep less"), L("Minus one")])
            Button {
                engine.adjust(by: 1)
            } label: {
                Text(verbatim: "+1")
                    .font(.title.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel(L("One rep more"))
            .accessibilityInputLabels([L("One rep more"), L("Plus one")])
        }
    }

    private var pauseButton: some View {
        Button {
            if engine.isPaused { engine.resume() } else { engine.pause() }
        } label: {
            Label(engine.isPaused ? L("Resume") : L("Pause"), systemImage: engine.isPaused ? "play.fill" : "pause.fill")
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        }
        .brandProminentButtonStyle()
        .disabled(engine.phase == .countdown || engine.phase == .finished
                  || engine.cameraInterruption != nil)
    }

    private var countdownOverlay: some View {
        ZStack {
            Color.screenBackground.opacity(0.92).ignoresSafeArea()
            VStack(spacing: 16) {
                Text(L("Get ready"))
                    .font(.title)
                    .foregroundStyle(.secondary)
                Text(verbatim: "\(engine.countdownValue)")
                    .font(.system(size: 180, weight: .black, design: .rounded))
                    .contentTransition(reduceMotion ? .identity : .numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.4)
                Text(L("Starting with \(engine.plan.first.displayName): hold still in the start position first"))
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// A pause is when someone stops to wonder whether they are doing it
    /// right, so the pause screen answers that: the current exercise, moving.
    /// It is also where the plan can be changed when it turns out too much.
    private var pauseOverlay: some View {
        ZStack {
            Color.screenBackground.opacity(0.97).ignoresSafeArea()
            VStack(spacing: 12) {
                Text(L("Paused"))
                    .font(.title3)
                    .foregroundStyle(.secondary)
                if let countdown = engine.resumeCountdown {
                    // Beeped as well, because the phone is back on the floor by
                    // now and nobody is looking at this.
                    Text(L("Continuing in \(countdown)"))
                        .font(.title2.bold())
                        .foregroundStyle(.brand)
                        .contentTransition(.numericText(countsDown: true))
                } else if let interruption = engine.cameraInterruption {
                    Label(interruption, systemImage: "camera.badge.ellipsis")
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                }
                Text(engine.exercise.displayName)
                    .font(.title.bold())
                    .foregroundStyle(.brand)
                ExerciseDemoView(exercise: engine.exercise)
                    .frame(maxHeight: 240)
                ForEach(Array(engine.exercise.demo.cues.prefix(2).enumerated()), id: \.offset) { _, cue in
                    Label(cue, systemImage: "checkmark.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Button {
                    editingPlan = true
                } label: {
                    Label(L("Edit workout"), systemImage: "slider.horizontal.3")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                HStack(spacing: 16) {
                    Button(role: .destructive) {
                        confirmAbort = true
                    } label: {
                        Label(L("Stop"), systemImage: "xmark")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                    }
                    .buttonStyle(.bordered)
                    Button {
                        engine.resume()
                    } label: {
                        Label(L("Resume"), systemImage: "play.fill")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                    }
                    .brandProminentButtonStyle()
                    // Resuming without frames would count nothing.
                    .disabled(engine.cameraInterruption != nil)
                }
                .controlSize(.large)
            }
            .padding()
        }
        .transition(.opacity)
    }

    private func errorOverlay(_ message: String) -> some View {
        ZStack {
            Color.screenBackground.opacity(0.95).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "camera.fill").font(.system(size: 60))
                Text(message).multilineTextAlignment(.center)
                Button(L("Back")) { dismiss() }
                    .brandProminentButtonStyle()
            }
            .padding()
        }
    }

    static func format(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.rounded(.up))
        return String(format: "%02d:%02d", total / 60, total % 60)
    }
}

/// The plan editor during a pause, for when the plan turns out to be too much (or too
/// little) halfway through. Edits a copy and hands it over on "Apply".
private struct WorkoutPlanSheet: View {
    let engine: WorkoutEngine
    let onApply: (WorkoutPlan) -> Void
    @State private var draft: WorkoutPlan
    @Environment(\.dismiss) private var dismiss

    init(engine: WorkoutEngine, onApply: @escaping (WorkoutPlan) -> Void) {
        self.engine = engine
        self.onApply = onApply
        _draft = State(initialValue: engine.plan)
    }

    var body: some View {
        NavigationStack {
            PlanEditorList(plan: $draft, addable: engine.calibratedExercises,
                           minimumMinutes: engine.shortestDurationAhead) {
                Section {
                } footer: {
                    Text(L("Changes count from now on and finished rounds stay. Only calibrated exercises can be added."))
                }
            }
            .navigationTitle(L("Edit workout"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Apply")) {
                        onApply(draft)
                        dismiss()
                    }
                    .disabled(draft == engine.plan)
                }
            }
        }
    }
}

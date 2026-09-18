import SwiftUI

struct CalibrationView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var engine: CalibrationEngine?

    var body: some View {
        Group {
            if let engine {
                CalibrationFlowView(engine: engine, onDone: {
                    model.reload()
                    dismiss()
                })
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("Calibration"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("Cancel")) {
                    engine?.cancel()
                    dismiss()
                }
            }
        }
        .onAppear {
            if engine == nil {
                // Only the gaps when a profile exists, e.g. squats after they moved to body pose.
                let missing = model.calibration?.missingExercises(for: model.plan) ?? []
                engine = CalibrationEngine(store: model.calibrationStore, existing: model.calibration,
                                           config: model.config,
                                           exercises: missing.isEmpty ? model.plan.exercises : missing)
            }
        }
        .onDisappear { engine?.cancel() }
    }
}

private struct CalibrationFlowView: View {
    @Bindable var engine: CalibrationEngine
    var onDone: () -> Void
    /// Off by default: counting needs no picture, the preview only helps placing the phone.
    @State private var showPreview = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        // At the largest text sizes a step outgrows the screen, and the button
        // at its bottom is the one that matters. Scroll only then, so the
        // spacers keep pinning that button to the bottom everywhere else.
        ViewThatFits(in: .vertical) {
            steps
            ScrollView { steps }
        }
    }

    private var steps: some View {
        VStack(spacing: 20) {
            switch engine.step {
            case .intro:
                intro
            case .ready(let exercise):
                ready(exercise)
            case .countdown(let exercise, let remaining):
                countdown(exercise, remaining)
            case .capturing(let exercise):
                capturing(exercise)
            case .succeeded(let exercise, let calibration):
                succeeded(exercise, calibration)
            case .failed(let exercise, let failure):
                failed(exercise, failure)
            case .done:
                done
            case .cameraError(let message):
                error(message)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Steps

    private var intro: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L("How it works"))
                .font(.title2.bold())
            bullet("iphone", L("Phone flat on the floor under the pull-up bar, screen facing up."))
            bullet("tshirt", L("Calibrate in your workout clothes, without a cap or hood."))
            bullet("figure.strengthtraining.traditional", L("One rep of each, one after another: \(engine.exerciseList). Hold still in the start position before every countdown."))
            bullet("lock.shield", L("Everything stays on the device. No images are stored."))
            Spacer()
            Button {
                Task { await engine.begin() }
            } label: {
                Text(L("Continue"))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .brandProminentButtonStyle()
            .controlSize(.large)
        }
    }

    private func ready(_ exercise: Exercise) -> some View {
        VStack(spacing: 16) {
            stepHeader(exercise)
            preview
            Text(instruction(for: exercise))
                .font(.body)
                .multilineTextAlignment(.center)
            ExerciseDemoButton(exercise: exercise)
            subjectIndicator
            Spacer()
            Button {
                engine.startExercise()
            } label: {
                Text(L("Start (\(engine.stepNumber)/\(engine.stepCount))"))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .brandProminentButtonStyle()
            .controlSize(.large)
        }
    }

    private func countdown(_ exercise: Exercise, _ remaining: Int) -> some View {
        VStack(spacing: 16) {
            stepHeader(exercise)
            Spacer()
            Text(verbatim: "\(remaining)")
                .font(.system(size: 160, weight: .black, design: .rounded))
                .contentTransition(reduceMotion ? .identity : .numericText())
                .lineLimit(1)
                .minimumScaleFactor(0.4)
            Text(L("Hold the start position"))
                .foregroundStyle(.secondary)
            Spacer()
        }
    }

    private func capturing(_ exercise: Exercise) -> some View {
        VStack(spacing: 16) {
            stepHeader(exercise)
            Text(L("Now: 1 \(exercise.singularName)"))
                .font(.largeTitle.bold())
            ProgressView(value: engine.captureProgress)
                .tint(.brand)
            subjectIndicator
            liveBar
            Spacer()
        }
    }

    private func succeeded(_ exercise: Exercise, _ calibration: ExerciseCalibration) -> some View {
        VStack(spacing: 16) {
            stepHeader(exercise)
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 80))
                .foregroundStyle(.green)
            Text(L("Detected"))
                .font(.title.bold())
            VStack(alignment: .leading, spacing: 4) {
                row(L("Duration"), L("\(calibration.repDuration.formattedDecimal(1)) s"))
                row(L("Signal"), String(format: "%.4f – %.4f", calibration.minValue, calibration.maxValue))
                row(L("Thresholds"), String(format: "%.4f / %.4f", calibration.low, calibration.high))
                row(L("Direction"), calibration.direction == .peak ? L("Deflection upward") : L("Deflection downward"))
            }
            .font(.footnote.monospacedDigit())
            .foregroundStyle(.secondary)
            Spacer()
            HStack {
                Button(L("Repeat")) { engine.retry() }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                Button {
                    engine.continueToNext()
                } label: {
                    Text(engine.stepNumber == engine.stepCount ? L("Done") : L("Continue"))
                        .frame(maxWidth: .infinity)
                }
                .brandProminentButtonStyle()
                .controlSize(.large)
            }
        }
    }

    private func failed(_ exercise: Exercise, _ failure: CalibrationFailure) -> some View {
        VStack(spacing: 16) {
            stepHeader(exercise)
            Image(systemName: "xmark.octagon.fill")
                .font(.system(size: 80))
                .foregroundStyle(.red)
            Text(L("Not detected"))
                .font(.title.bold())
            Text(failure.message)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Spacer()
            Button {
                engine.retry()
            } label: {
                Text(L("Repeat"))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .brandProminentButtonStyle()
            .controlSize(.large)
        }
    }

    private var done: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 80))
                .foregroundStyle(.green)
            Text(L("Calibration saved"))
                .font(.title.bold())
            Spacer()
            Button {
                onDone()
            } label: {
                Text(L("Back to start"))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .brandProminentButtonStyle()
            .controlSize(.large)
        }
    }

    private func error(_ message: String) -> some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "camera.fill")
                .font(.system(size: 60))
            Text(message)
                .multilineTextAlignment(.center)
            if let url = URL(string: UIApplication.openSettingsURLString) {
                Link(L("Open Settings"), destination: url)
            }
            Spacer()
        }
    }

    // MARK: - Pieces

    private func stepHeader(_ exercise: Exercise) -> some View {
        VStack(spacing: 4) {
            Text(L("Step \(engine.stepNumber) of \(engine.stepCount)"))
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(exercise.singularName)
                .font(.title.bold())
        }
    }

    @ViewBuilder
    private var preview: some View {
        if showPreview {
            CameraPreviewView(session: engine.camera.session)
                .frame(height: 180)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        Button {
            showPreview.toggle()
        } label: {
            Label(showPreview ? L("Hide camera preview") : L("Show camera preview"),
                  systemImage: showPreview ? "video.slash" : "video.fill")
                .font(.subheadline)
        }
        .buttonStyle(.bordered)
    }

    private var subjectIndicator: some View {
        let detected = engine.subjectDetected
        let isFace = engine.trackedSource == .face
        return Label(isFace ? (detected ? L("Face detected") : L("No face in frame"))
                            : (detected ? L("Person detected") : L("No person in frame")),
                     systemImage: isFace ? (detected ? "face.smiling" : "face.dashed")
                                         : (detected ? "figure.stand" : "person.fill.questionmark"))
            .foregroundStyle(detected ? Color.green : Color.brand)
            .font(.subheadline)
    }

    private var liveBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: String(format: "\(L("Signal")): %.4f", engine.liveValue ?? 0))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    private func bullet(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .frame(width: 24)
                .foregroundStyle(.brand)
            Text(text)
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
        }
    }

    private func instruction(for exercise: Exercise) -> String {
        switch exercise {
        case .pullUp:
            return L("Hang from the bar with straight arms and stay still. After the countdown: one pull-up until your chin is over the bar, then hang all the way down again.")
        case .pushUp:
            return L("Get into the push-up position with straight arms, face above the phone. After the countdown: one push-up, then stay at the top.")
        case .squat:
            return L("Toes just behind the phone, then keep still; you can look wherever you like. After the countdown: one squat, then stand up straight again.")
        case .plank:
            return "" // never calibrated: holds run on a timer
        }
    }
}

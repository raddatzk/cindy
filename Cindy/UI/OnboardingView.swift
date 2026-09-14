import SwiftUI

/// The first-run introduction: what the workout is, how Cindy counts it, where
/// to put the phone, what the exercises look like, and why calibration comes
/// first. Reachable again from Settings, where the calibration hand-off is
/// left out because the user is already somewhere on purpose.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// Hand-off to calibration on the last page. Omitted when the intro is
    /// opened from Settings rather than at first launch.
    var onCalibrate: (() -> Void)?

    @State private var page = Page.welcome
    @State private var demoExercise: Exercise?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Page: Int, CaseIterable {
        case welcome, counting, setup, movements, calibration
    }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                welcome.tag(Page.welcome)
                counting.tag(Page.counting)
                setup.tag(Page.setup)
                movements.tag(Page.movements)
                calibration.tag(Page.calibration)
            }
            .tabViewStyle(.page)
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            footer
        }
        .background(Color.screenBackground)
        .onAppear { demoExercise = model.plan.first }
    }

    // MARK: - Pages

    private var welcome: some View {
        page(icon: "flame.fill", title: L("Welcome to Cindy")) {
            Text(L("Cindy is a CrossFit benchmark workout: one round is \(model.plan.summary). You repeat that round as often as you can in \(model.plan.durationMinutes) minutes — that is what AMRAP means."))
            Text(L("Your score is rounds plus the reps of the round you did not finish. The plan is yours to change at any time."))
        }
    }

    private var counting: some View {
        page(icon: nil, title: L("Cindy counts for you")) {
            Text(L("Put the phone down and train. The front camera watches how large your face is in the picture — that changes with every rep, and Cindy turns it into a count."))
            Label(L("Every frame is analyzed on the iPhone and discarded right away. No photos, no video, no internet connection."), systemImage: "lock.shield")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        } illustration: {
            PulsingFaceIcon()
        }
    }

    private var setup: some View {
        page(icon: "iphone.gen3", title: L("Where the phone goes")) {
            bullet("arrow.down.to.line", L("Phone on the floor below you, screen facing up."))
            bullet("angle", L("Better: tilt it 30–45°, for example against a weight plate. That improves the signal a lot."))
            bullet("tshirt", L("Train in the clothes you calibrated in, without a cap or hood."))
            bullet("speaker.wave.2", L("Cindy beeps for every rep and calls out each new round and exercise, so you never have to look at the screen."))
        }
    }

    private var movements: some View {
        page(icon: nil, title: L("This is what a rep looks like")) {
            Picker(L("Exercise"), selection: $demoExercise) {
                ForEach(model.plan.exercises) { exercise in
                    Text(exercise.displayName).tag(Exercise?.some(exercise))
                }
            }
            .pickerStyle(.segmented)
            Text(L("Tap through the exercises. The phone in the drawing shows where it has to lie for Cindy to see your face."))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        } illustration: {
            if let demoExercise {
                ExerciseDemoView(exercise: demoExercise, isPaused: page != .movements)
                    .frame(maxHeight: 220)
            }
        }
    }

    private var calibration: some View {
        page(icon: "scope", title: L("One calibration first")) {
            Text(L("Everybody moves differently, so Cindy measures you once: one rep of each exercise, one after another. It takes about two minutes."))
            Text(L("Repeat it when you change where the phone lies, or when counting starts to drift."))
        }
    }

    // MARK: - Chrome

    private var footer: some View {
        VStack(spacing: 8) {
            Button {
                advance()
            } label: {
                Text(primaryTitle)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .brandProminentButtonStyle()
            .controlSize(.large)

            Button(page == .calibration ? L("Not now") : L("Skip")) { finish() }
                .font(.subheadline)
                .opacity(onCalibrate == nil && page == .calibration ? 0 : 1)
                .disabled(onCalibrate == nil && page == .calibration)
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }

    private var primaryTitle: String {
        guard page == .calibration else { return L("Continue") }
        return onCalibrate == nil ? L("Done") : L("Start calibration")
    }

    private func advance() {
        guard page == .calibration else {
            withAnimation(reduceMotion ? nil : .default) { page = Page(rawValue: page.rawValue + 1) ?? .calibration }
            return
        }
        let calibrate = onCalibrate
        finish()
        calibrate?()
    }

    private func finish() {
        model.markIntroSeen()
        dismiss()
    }

    /// Shared page layout: an illustration on top, then title and copy.
    private func page<Content: View, Illustration: View>(
        icon: String?,
        title: String,
        @ViewBuilder content: () -> Content,
        @ViewBuilder illustration: () -> Illustration
    ) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack {
                    if let icon {
                        Image(systemName: icon)
                            .font(.system(size: 64))
                            .foregroundStyle(.brand)
                            .padding(.vertical, 24)
                    }
                    illustration()
                }
                .frame(maxWidth: .infinity)

                Text(title)
                    .font(.title.bold())
                content()
            }
            .padding(.horizontal)
            .padding(.top, 24)
            // Room for the page dots the TabView draws over the content.
            .padding(.bottom, 44)
        }
    }

    private func page<Content: View>(icon: String?, title: String,
                                     @ViewBuilder content: () -> Content) -> some View {
        page(icon: icon, title: title, content: content, illustration: { EmptyView() })
    }

    private func bullet(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .frame(width: 24)
                .foregroundStyle(.brand)
            Text(text)
        }
    }
}

/// A face that drifts closer and further away — the signal Cindy actually
/// measures, as a picture.
private struct PulsingFaceIcon: View {
    /// Held still with Reduce Motion: this one is an illustration, not a demo
    /// anyone needs to see move.
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { context in
            let phase = StickFigureDemoView.phase(at: context.date, cycle: 2.4)
            ZStack {
                Image(systemName: "iphone.gen3")
                    .font(.system(size: 96))
                    .foregroundStyle(.secondary)
                Image(systemName: "face.smiling.inverse")
                    .font(.system(size: 40))
                    .foregroundStyle(.brand)
                    .scaleEffect(0.7 + 0.5 * phase)
                    .offset(y: -8 - 40 * (1 - phase))
            }
        }
        .frame(height: 170)
        .accessibilityHidden(true)
    }
}

import SwiftUI

/// The first-run introduction: what the workout is, that the phone goes down once
/// and nothing leaves it, what each exercise looks like around that phone, and why
/// calibration comes first. Reachable again from Settings, where the calibration
/// hand-off is left out because the user is already somewhere on purpose.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// Hand-off to calibration on the last page. Omitted when the intro is
    /// opened from Settings rather than at first launch.
    var onCalibrate: (() -> Void)?

    @State private var page = Page.welcome
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Page: Hashable {
        case welcome, setup
        case exercise(Exercise)
        case calibration
    }

    /// One page per exercise of the current plan, between the setup and calibration.
    private var pages: [Page] {
        [.welcome, .setup] + model.plan.exercises.map(Page.exercise) + [.calibration]
    }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                ForEach(pages, id: \.self) { page in
                    content(for: page).tag(page)
                }
            }
            .tabViewStyle(.page)
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            footer
        }
        .background(Color.screenBackground)
        // Set here rather than inherited: the first-run cover sits outside the tinted
        // navigation stack, the Settings sheet inside it, and both must look the same.
        .tint(.brand)
    }

    @ViewBuilder
    private func content(for page: Page) -> some View {
        switch page {
        case .welcome: welcome
        case .setup: setup
        case .exercise(let exercise): exercisePage(exercise)
        case .calibration: calibration
        }
    }

    // MARK: - Pages

    private var welcome: some View {
        page(icon: "flame.fill", title: L("Welcome to Cindy")) {
            Text(L("Cindy is a CrossFit benchmark workout: one round is \(model.plan.summary). You repeat that round as often as you can in \(model.plan.durationMinutes) minutes — that is what AMRAP means."))
            Text(L("Your score is rounds plus the reps of the round you did not finish. The plan is yours to change at any time."))
        }
    }

    private var setup: some View {
        page(icon: nil, title: L("Put the phone down once")) {
            bullet("iphone.gen3", L("Phone flat on the floor under the pull-up bar, screen facing up."))
            bullet("hand.raised", L("It stays there for the whole workout. You never touch it or look at it again."))
            bullet("speaker.wave.2", L("Cindy beeps for every rep, with a higher tone when an exercise is done."))
            Label(L("Every frame is analyzed on the iPhone and discarded right away. No photos, no video, no internet connection."), systemImage: "lock.shield")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if !CameraSession.hasDepthCamera {
                // No TrueDepth camera (iPhone SE, iPhone Duo): counting falls back to the picture alone.
                Label(L("This iPhone has no Face ID camera, so Cindy counts from the camera picture alone. That is less reliable; correct with +1 and −1 when it misses a rep."), systemImage: "exclamationmark.triangle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        } illustration: {
            CountingPhoneIcon()
        }
    }

    private func exercisePage(_ exercise: Exercise) -> some View {
        let demo = exercise.demo
        return page(icon: nil, title: exercise.displayName) {
            bullet("iphone.gen3", demo.placement)
            bullet("figure.strengthtraining.functional", demo.keyCue)
        } illustration: {
            // Without the Reduce Motion play button of `ExerciseDemoView`: the pager
            // already pauses every page that is not on screen.
            StickFigureDemoView(demo: demo, isPaused: page != .exercise(exercise) || reduceMotion)
                .frame(maxHeight: 220)
        }
    }

    private var calibration: some View {
        page(icon: "scope", title: L("One calibration first")) {
            Text(L("Everybody moves differently, so Cindy measures you once: one rep of each exercise, one after another. It takes about two minutes."))
            Text(L("Repeat it when you change where the phone lies, or when counting starts to drift."))
            bullet("tshirt", L("Train in the clothes you calibrated in, without a cap or hood."))
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
            let next = pages.firstIndex(of: page).map { pages.index(after: $0) } ?? pages.endIndex
            withAnimation(reduceMotion ? nil : .default) { page = next < pages.endIndex ? pages[next] : .calibration }
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

/// A phone lying on the floor that keeps counting — Cindy's job, as a picture.
private struct CountingPhoneIcon: View {
    /// Held on one number with Reduce Motion: an illustration, not a demo anyone needs to see move.
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let interval = 0.9

    var body: some View {
        TimelineView(.animation(minimumInterval: interval / 3, paused: reduceMotion)) { context in
            let count = reduceMotion ? 7 : Int(context.date.timeIntervalSinceReferenceDate / interval) % 15 + 1
            ZStack {
                Image(systemName: "iphone.gen3")
                    .font(.system(size: 96))
                    .foregroundStyle(.secondary)
                Text(verbatim: "\(count)")
                    .font(.system(size: 40, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.brand)
                    .contentTransition(.numericText())
                    .animation(reduceMotion ? nil : .snappy, value: count)
            }
        }
        .frame(height: 170)
        .accessibilityHidden(true)
    }
}

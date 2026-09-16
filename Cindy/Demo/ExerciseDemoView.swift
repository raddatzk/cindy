import SwiftUI

/// Shows what one exercise looks like, picking whichever renderer can actually
/// draw it: the bundled USDZ through RealityKit, otherwise the stick figure.
struct ExerciseDemoView: View {
    enum Renderer {
        /// The 3D model when one is bundled, otherwise the stick figure.
        case automatic
        /// Always the stick figure: it also draws the floor, the bar and the phone.
        case stickFigure
    }

    let exercise: Exercise
    var renderer: Renderer = .automatic
    /// Set while the view is off screen so the animation stops costing frames.
    var isPaused: Bool = false
    @State private var modelFailed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// With Reduce Motion on, a demo waits to be started instead of looping
    /// the moment it appears. It is still the whole point of the screen, so
    /// one tap plays it.
    @State private var playRequested = false

    var body: some View {
        let paused = isPaused || (reduceMotion && !playRequested)
        renderer(paused: paused)
            .overlay(alignment: .bottomTrailing) {
                if reduceMotion {
                    Button {
                        playRequested.toggle()
                    } label: {
                        Label(playRequested ? L("Pause movement") : L("Play movement"),
                              systemImage: playRequested ? "pause.circle.fill" : "play.circle.fill")
                            .labelStyle(.iconOnly)
                            .font(.largeTitle)
                    }
                    .buttonStyle(.borderless)
                    .padding(8)
                }
            }
            // A model that failed belongs to one exercise, not to the next.
            .onChange(of: exercise) { modelFailed = false }
    }

    @ViewBuilder
    private func renderer(paused: Bool) -> some View {
        let demo = exercise.demo
        if renderer == .automatic, let url = Self.modelURL(for: demo), !modelFailed {
            RealityDemoView(url: url, perspective: demo.perspective, isPaused: paused) { modelFailed = true }
                .aspectRatio(1, contentMode: .fit)
                // A RealityView builds its scene once; a new URL alone leaves
                // the previous exercise's model playing. A new identity per
                // exercise rebuilds it — the onboarding picker switches in place.
                .id(exercise)
        } else {
            StickFigureDemoView(demo: demo, isPaused: paused)
        }
    }

    /// The bundled animation for this exercise, if one has been added.
    ///
    /// Nothing bundled means the stick figure — that is the case for any
    /// exercise whose model has not been built yet, not an error. Built with
    /// `tools/build_exercise_usdz.py`.
    static func modelURL(for demo: ExerciseDemo) -> URL? {
        return Bundle.main.url(forResource: demo.modelName, withExtension: "usdz")
    }
}

/// The demo plus the form cues, as a sheet.
struct ExerciseDemoSheet: View {
    let exercise: Exercise
    var renderer: ExerciseDemoView.Renderer = .automatic
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    ExerciseDemoView(exercise: exercise, renderer: renderer)
                        .padding(renderer == .stickFigure ? 16 : 0)
                        .frame(maxWidth: .infinity)
                        .frame(maxHeight: 320)
                        .background(Color.panelBackground, in: RoundedRectangle(cornerRadius: 16))

                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(Array(exercise.demo.cues.enumerated()), id: \.offset) { index, cue in
                            cueRow(number: index + 1, cue)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding()
            }
            .navigationTitle(exercise.singularName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Done")) { dismiss() }
                }
            }
        }
    }

    private func cueRow(number: Int, _ cue: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number.formatted(.number.locale(Localization.locale)))
                .font(.caption.bold())
                .foregroundStyle(.onBrand)
                .frame(width: 22, height: 22)
                .background(Color.brand, in: Circle())
            Text(cue)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Opens `ExerciseDemoSheet`. Used wherever an exercise is named and the user
/// may not know what it is supposed to look like.
struct ExerciseDemoButton: View {
    enum Style {
        /// A full-width bordered button with a title.
        case prominent
        /// A bare icon, for list rows that are already busy.
        case icon
    }

    let exercise: Exercise
    var style: Style = .prominent
    var renderer: ExerciseDemoView.Renderer = .automatic
    @State private var showingDemo = false

    var body: some View {
        Button {
            showingDemo = true
        } label: {
            switch style {
            case .prominent:
                Label(L("Show me the movement"), systemImage: "figure.strengthtraining.traditional")
            case .icon:
                // Styled here, not on the button: the sheet would inherit a style set outside.
                // `Color.secondary`, not `.secondary`: inside a button the latter is derived from the tint.
                Image(systemName: "questionmark.circle")
                    .foregroundStyle(Color.secondary)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L("Show the \(exercise.singularName) movement"))
        .sheet(isPresented: $showingDemo) {
            ExerciseDemoSheet(exercise: exercise, renderer: renderer)
        }
    }
}

#Preview {
    ExerciseDemoSheet(exercise: .pushUp)
}

import SwiftUI

/// Shows what one exercise looks like, as a looping stick figure that also
/// draws the floor, the bar and the phone.
struct ExerciseDemoView: View {
    let exercise: Exercise
    /// Set while the view is off screen so the animation stops costing frames.
    var isPaused: Bool = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// With Reduce Motion on, a demo waits to be started instead of looping
    /// the moment it appears. It is still the whole point of the screen, so
    /// one tap plays it.
    @State private var playRequested = false

    var body: some View {
        let paused = isPaused || (reduceMotion && !playRequested)
        StickFigureDemoView(demo: exercise.demo, isPaused: paused)
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
    }
}

/// The demo plus the form cues, as a sheet.
struct ExerciseDemoSheet: View {
    let exercise: Exercise
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    ExerciseDemoView(exercise: exercise)
                        .padding(16)
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
            ExerciseDemoSheet(exercise: exercise)
        }
    }
}

#Preview {
    ExerciseDemoSheet(exercise: .pushUp)
}

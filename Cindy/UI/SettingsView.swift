import SwiftUI

/// App settings: appearance, Apple Health, reminder, privacy information and
/// debug recordings.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    /// Writing to Health was refused — only the system Settings can undo that.
    @State private var healthDenied = false
    /// Saved workouts that are not in Health yet.
    @State private var healthPending = 0
    @State private var healthExporting = false
    @State private var healthError: String?
    /// Notifications were refused — only the system Settings can undo that.
    @State private var remindersDenied = false
    /// When the notification that is actually pending will fire. Read back
    /// from the system rather than from the plan, so a nudge that failed to
    /// schedule shows up as missing instead of looking fine.
    @State private var pendingReminder: Date?
    @State private var showingIntro = false

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                Picker(L("Language"), selection: $model.language) {
                    ForEach(AppLanguage.allCases) { language in
                        Text(language.title).tag(language)
                    }
                }
                Picker(L("Appearance"), selection: $model.theme) {
                    ForEach(AppTheme.allCases) { theme in
                        Text(theme.title).tag(theme)
                    }
                }
            } header: {
                Text(L("Display"))
            } footer: {
                Text(L("“Automatic” follows the device setting."))
            }

            Section {
                Button {
                    showingIntro = true
                } label: {
                    Label(L("How Cindy works"), systemImage: "questionmark.circle")
                }
            } footer: {
                Text(L("The introduction from the first launch: where the phone goes, what each exercise looks like and why nothing leaves your iPhone."))
            }

            if model.isHealthAvailable {
                healthSection
            }

            reminderSection

            Section {
                privacyRow("video.slash", L("No video or photo recording"),
                           L("The camera only runs during calibration and your workout. Every frame is analyzed on the iPhone right away and then discarded. No photos or videos are stored."))
                privacyRow("wifi.slash", L("Nothing is sent anywhere"),
                           L("Cindy has no servers, no accounts and never opens an internet connection. Your data only leaves the device if you share it yourself."))
                privacyRow("internaldrive", L("What is stored locally"),
                           L("Calibration, workout plan and history live in the app's storage. They may be part of your personal iPhone backup and are removed when you delete the app."))
            } header: {
                Text(L("Privacy"))
            }

            Section {
                Toggle(L("Record workouts"), isOn: $model.recordWorkouts)
                NavigationLink(value: Route.recordings) {
                    Text(L("Recordings"))
                }
            } header: {
                Text(L("Signal recordings"))
            } footer: {
                Text(L("Writes a CSV file during every workout with measurements such as face size, signal and rep count. It contains only numbers and state, no images. Helps with debugging when counting goes wrong. About \(Self.recordingSizePerSession) per 20 minutes."))
            }

            Section {
                LabeledContent(L("Version"), value: Self.versionString)
            }
        }
        .navigationTitle(L("Settings"))
        .onAppear { healthPending = model.workoutsPendingInHealth }
        .task { pendingReminder = await model.reminder.pendingDate() }
        .sheet(isPresented: $showingIntro) {
            OnboardingView()
        }
    }

    // MARK: - Reminder

    private var reminderSection: some View {
        Section {
            Toggle(L("Remind me"), isOn: reminderBinding)
            if model.remindersEnabled, let pendingReminder {
                LabeledContent(L("Next reminder"),
                               value: pendingReminder.formatted(date: .abbreviated, time: .shortened,
                                                                in: Localization.locale))
            }
        } header: {
            Text(L("Reminder"))
        } footer: {
            Text(remindersDenied
                 ? L("Notifications are switched off for Cindy. Turn them on in Settings › Apps › Cindy › Notifications.")
                 : L("Cindy picks the moment from how hard the last session was, how you have recovered and when you usually train — and moves it when your body says so. It is scheduled on the device; nothing is sent anywhere."))
        }
    }

    private var reminderBinding: Binding<Bool> {
        Binding(get: { model.remindersEnabled },
                set: { isOn in
                    guard isOn else {
                        remindersDenied = false
                        model.disableReminders()
                        pendingReminder = nil
                        return
                    }
                    Task {
                        remindersDenied = await model.enableReminders() == false
                        pendingReminder = await model.reminder.pendingDate()
                    }
                })
    }

    // MARK: - Apple Health

    private var healthSection: some View {
        Section {
            Toggle(L("Sync to Apple Health"), isOn: healthSyncBinding)
            if model.syncToHealth, healthPending > 0 {
                Button(action: exportHistory) {
                    HStack {
                        Text(L("Export earlier workouts"))
                        Spacer()
                        if healthExporting {
                            ProgressView()
                        } else {
                            Text(healthPending.formatted(.number.locale(Localization.locale)))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(healthExporting)
            }
        } header: {
            Text(L("Apple Health"))
        } footer: {
            Text(healthFooter)
        }
    }

    private var healthFooter: String {
        if let healthError { return healthError }
        if healthDenied {
            return L("Cindy may not write to Apple Health. Allow it in Settings › Apps › Health › Data Access.")
        }
        return L("Saved workouts are written to Health as cross-training sessions with one segment per round. Cindy also reads sleep, resting pulse, HRV and body weight — for the readiness estimate and the energy calculation, nothing else. With the switch off Cindy does not touch Health at all.")
    }

    private var healthSyncBinding: Binding<Bool> {
        Binding(get: { model.syncToHealth },
                set: { isOn in
                    healthError = nil
                    guard isOn else {
                        healthDenied = false
                        model.disableHealthSync()
                        return
                    }
                    Task {
                        healthDenied = await model.enableHealthSync() == false
                        healthPending = model.workoutsPendingInHealth
                    }
                })
    }

    private func exportHistory() {
        healthExporting = true
        healthError = nil
        Task {
            do {
                _ = try await model.exportHistoryToHealth()
            } catch {
                healthError = error.localizedDescription
            }
            healthPending = model.workoutsPendingInHealth
            healthExporting = false
        }
    }

    private func privacyRow(_ icon: String, _ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(.brand)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    /// Rough CSV size of one 20-minute session, formatted for the app language.
    private static var recordingSizePerSession: String {
        Int64(4_500_000).formattedFileSize
    }

    private static var versionString: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}

/// CSV files in Documents/DebugLogs with share and delete.
struct RecordingsView: View {
    @State private var files: [URL] = []

    var body: some View {
        Group {
            if files.isEmpty {
                ContentUnavailableView(L("No recordings"), systemImage: "waveform.path.ecg",
                                       description: Text(L("Turn on “Record workouts” and do a workout.")))
            } else {
                List {
                    ForEach(files, id: \.self) { url in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(url.lastPathComponent)
                                    .font(.caption.monospaced())
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                Text(Self.size(of: url))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            ShareLink(item: url) {
                                Image(systemName: "square.and.arrow.up")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(L("Share recording"))
                        }
                    }
                    .onDelete { offsets in
                        for index in offsets { try? FileManager.default.removeItem(at: files[index]) }
                        reload()
                    }
                }
            }
        }
        .navigationTitle(L("Recordings"))
        .onAppear(perform: reload)
    }

    private func reload() {
        let directory = FrameLogger.logsDirectory
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        files = urls.filter { $0.pathExtension == "csv" }.sorted { $0.lastPathComponent > $1.lastPathComponent }
    }

    private static func size(of url: URL) -> String {
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return Int64(bytes).formattedFileSize
    }
}

import SwiftUI
import SofaKingCore

/// The DVR tab: what is recording now, then what was recorded, newest first, then what is scheduled,
/// soonest first. Selecting a recorded or running one plays it, resuming where the household stopped; holding
/// select offers From start and Delete (Stop on a running one, Cancel on a scheduled one), which asks first. The
/// series rules follow, each with whether it takes repeats, how many recordings it keeps, and Stop series.
struct RecordingsView: View {
    @Environment(AppModel.self) private var model
    @State private var playing: VodRequest?
    @State private var deleting: DvrRecording?
    @State private var failed = false

    var body: some View {
        NavigationStack {
            content.navigationTitle("DVR")
        }
        .onAppear { model.core.refreshRecordings() }
        .fullScreenCover(item: $playing) { request in
            VodPlayerView(request: request)
        }
        .confirmationDialog(
            deleting.map { $0.deleteQuestion } ?? "",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            titleVisibility: .visible,
            presenting: deleting
        ) { recording in
            Button(recording.deleteLabel, role: .destructive) {
                Task {
                    let done = (try? await model.core.deleteRecording(recording: recording))?.boolValue ?? false
                    failed = !done
                }
            }
            Button("Keep it", role: .cancel) {}
        }
        .alert("The server didn't take that change. Try again.", isPresented: $failed) {
            Button("OK") {}
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.recordings {
        case let ready as RecordingsStateReady:
            list(ready)
        case let failed as RecordingsStateFailed:
            VStack(spacing: 32) {
                Text("The recordings didn't load").font(.title2)
                Text(failed.message).foregroundStyle(.secondary)
                Button("Try again") { model.core.refreshRecordings() }
            }
        case is RecordingsStateUnavailable:
            ContentUnavailableView("This server does not record", systemImage: "record.circle")
        default:
            ProgressView("Sofa King close, loading recordings…")
        }
    }

    private func list(_ ready: RecordingsStateReady) -> some View {
        let groups = RecordingGroup.groups(ready.recordings) { model.core.recordingStartMs(recording: $0) }
        return List {
            Section {
                SkipModePicker(title: "Skip commercials", kind: .commercialBreaks)
            } footer: {
                if ready.space.totalBytes > 0 {
                    Text("\(bytes(ready.space.freeBytes)) free of \(bytes(ready.space.totalBytes))")
                } else {
                    Text("Automatically, playback jumps over the breaks the server found in a recording. With a button, Skip break waits for a press. Off, breaks play.")
                }
            }
            if groups.isEmpty {
                ContentUnavailableView(
                    "Nothing recorded yet",
                    systemImage: "record.circle",
                    description: Text("Open a program's details in the guide and choose Record.")
                )
            }
            ForEach(groups, id: \.0) { group, recordings in
                Section(group.title) {
                    ForEach(recordings) { recording in
                        Button {
                            if recording.playable {
                                playing = VodRequest(recording: recording, fromStart: false)
                            } else {
                                deleting = recording
                            }
                        } label: {
                            RecordingRow(recording: recording)
                        }
                        .contextMenu {
                            if recording.playable && recording.inProgress {
                                Button("From start") { playing = VodRequest(recording: recording, fromStart: true) }
                            }
                            Button(recording.deleteLabel, role: .destructive) { deleting = recording }
                        }
                    }
                }
            }
            if !ready.rules.isEmpty {
                Section("Series") {
                    ForEach(ready.rules, id: \.id) { rule in
                        SeriesRow(rule: rule) { failed = true }
                    }
                }
            }
        }
    }

    private func bytes(_ count: Int64) -> String {
        count.formatted(.byteCount(style: .decimal))
    }
}

/// One recording: its title, the episode and when it aired, and how far it got.
private struct RecordingRow: View {
    @Environment(AppModel.self) private var model
    let recording: DvrRecording

    var body: some View {
        HStack(spacing: 24) {
            VStack(alignment: .leading, spacing: 6) {
                Text(recording.title).font(.headline).lineLimit(1)
                Text(model.core.recordingSubtitle(recording)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if let status {
                Text(status).font(.caption).foregroundStyle(recording.state == .failed ? .red : .secondary)
            }
        }
    }

    private var status: String? {
        switch recording.state {
        case .recording:
            let end = Date(timeIntervalSince1970: Double(model.core.recordingEndMs(recording: recording)) / 1000)
            return "Recording until \(end.formatted(date: .omitted, time: .shortened))"
        case .failed:
            return recording.reason.map { "Failed: \($0)" } ?? "Failed"
        case .completed:
            if recording.watched { return "Watched" }
            if recording.inProgress { return "Started" }
            return nil
        default:
            return nil
        }
    }
}

/// One series rule: what it takes and keeps, with buttons that change them and Stop series, which asks first.
private struct SeriesRow: View {
    @Environment(AppModel.self) private var model
    let rule: SeriesRule
    let refused: () -> Void
    @State private var stopping = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(rule.title).font(.headline).lineLimit(1)
            Text(summary).font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 24) {
                Button(rule.newOnly ? "Record repeats too" : "New episodes only") {
                    change { try await model.core.updateRule(rule: rule, newOnly: !rule.newOnly, keep: rule.keep) }
                }
                Button("Keep: \(rule.keep == 0 ? "All" : String(rule.keep))") {
                    change { try await model.core.updateRule(rule: rule, newOnly: rule.newOnly, keep: SeriesRow.nextKeep(rule.keep)) }
                }
                Button("Stop series", role: .destructive) { stopping = true }
            }
        }
        .confirmationDialog("Stop recording \(rule.title)? What it recorded so far is kept.", isPresented: $stopping, titleVisibility: .visible) {
            Button("Stop series", role: .destructive) { change { try await model.core.deleteRule(rule: rule) } }
            Button("Keep it", role: .cancel) {}
        }
    }

    /// "New episodes only · Keeps 5 · Any channel"
    private var summary: String {
        [
            rule.newOnly ? "New episodes only" : "All airings",
            rule.keep == 0 ? "Keeps all" : "Keeps \(rule.keep)",
            rule.channelId == nil ? "Any channel" : nil,
        ].compactMap { $0 }.joined(separator: " · ")
    }

    private func change(_ action: @escaping () async throws -> KotlinBoolean) {
        Task {
            if !((try? await action())?.boolValue ?? false) { refused() }
        }
    }

    /// All, then 1, 3, 5 and 10, then all again, as the other apps step; a count set elsewhere goes to the next step above it.
    static func nextKeep(_ keep: Int32) -> Int32 {
        [1, 3, 5, 10].first { $0 > keep } ?? 0
    }
}

/// The list's groups, in the order they show.
private enum RecordingGroup: Hashable {
    case now, recorded, scheduled

    var title: String {
        switch self {
        case .now: "Recording now"
        case .recorded: "Recorded"
        case .scheduled: "Scheduled"
        }
    }

    /// Running ones first, then finished and failed ones newest first, then scheduled ones soonest first; empty
    /// groups are left out. A state a newer server added counts as recorded.
    static func groups(_ recordings: [DvrRecording], startMs: (DvrRecording) -> Int64) -> [(RecordingGroup, [DvrRecording])] {
        let byGroup = Dictionary(grouping: recordings) { recording -> RecordingGroup in
            switch recording.state {
            case .recording: .now
            case .scheduled: .scheduled
            default: .recorded
            }
        }
        return [RecordingGroup.now, .recorded, .scheduled].compactMap { group in
            guard let list = byGroup[group], !list.isEmpty else { return nil }
            let sorted = group == .scheduled ? list.sorted { startMs($0) < startMs($1) } : list.sorted { startMs($0) > startMs($1) }
            return (group, sorted)
        }
    }
}

extension DvrRecording: @retroactive Identifiable {}

extension DvrRecording {
    /// What ending it is called: a running one stops, a scheduled one is canceled, anything else is deleted.
    var deleteLabel: String {
        switch state {
        case .recording: "Stop"
        case .scheduled: "Cancel recording"
        default: "Delete"
        }
    }

    var deleteQuestion: String {
        switch state {
        case .recording: "Stop recording \(title)? What it recorded so far is kept."
        case .scheduled: "Cancel the recording of \(title)?"
        default: "Delete \(title) and its file for good?"
        }
    }
}

extension SofaKingTv {
    /// The episode, when the guide gave one, then the channel and when it aired, such as "S2 E5 · News 4 · Sun Oct 18, 6:00 PM".
    func recordingSubtitle(_ recording: DvrRecording) -> String {
        let number = [recording.season.map { "S\($0.intValue)" }, recording.episode.map { "E\($0.intValue)" }]
            .compactMap { $0 }.joined(separator: " ")
        let aired = Date(timeIntervalSince1970: Double(recordingStartMs(recording: recording)) / 1000)
            .formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day().hour().minute())
        return [number.isEmpty ? nil : number, recording.episodeTitle, recording.channelName.isEmpty ? nil : recording.channelName, aired]
            .compactMap { $0 }.joined(separator: " · ")
    }
}

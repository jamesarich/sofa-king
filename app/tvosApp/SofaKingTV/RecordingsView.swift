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
            content.navigationTitle(strings.tabDvr)
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
            Button(recordingStrings.keepIt, role: .cancel) {}
        }
        .alert(strings.changeFailed, isPresented: $failed) {
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
                Button(strings.retry) { model.core.refreshRecordings() }
            }
        case is RecordingsStateUnavailable:
            ContentUnavailableView(recordingStrings.unavailable, systemImage: "record.circle")
        default:
            ProgressView(recordingStrings.loading)
        }
    }

    private func list(_ ready: RecordingsStateReady) -> some View {
        let groups = model.core.recordingGroups(recordings: ready.recordings)
        return List {
            Section {
                SkipModePicker(title: recordingStrings.skipCommercials, kind: .commercialBreaks)
            } footer: {
                if ready.space.totalBytes > 0 {
                    Text(recordingStrings.space(free: bytes(ready.space.freeBytes), total: bytes(ready.space.totalBytes)))
                } else {
                    Text(recordingStrings.skipCommercialsHelp)
                }
            }
            if groups.isEmpty {
                ContentUnavailableView(
                    recordingStrings.empty,
                    systemImage: "record.circle",
                    description: Text(recordingStrings.emptyHint)
                )
            }
            ForEach(groups, id: \.group) { entry in
                Section(entry.group.title) {
                    ForEach(entry.recordings) { recording in
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
                                Button(recordingStrings.fromStart) { playing = VodRequest(recording: recording, fromStart: true) }
                            }
                            Button(recording.deleteLabel, role: .destructive) { deleting = recording }
                        }
                    }
                }
            }
            if !ready.rules.isEmpty {
                Section(recordingStrings.series) {
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

    /// Why a kept recording ended before its program did, if it did.
    private var endedEarly: String? {
        guard let reason = recording.endedEarly else { return nil }
        return recordingStrings.endedEarlyLabel(reason: reason)
    }

    private var status: String? {
        switch recording.state {
        case .recording:
            let end = Date(timeIntervalSince1970: Double(model.core.recordingEndMs(recording: recording)) / 1000)
            return recordingStrings.until(end: end.formatted(date: .omitted, time: .shortened))
        case .failed:
            return recordingStrings.failureLabel(failure: recording.failure)
        case .completed:
            let watch: String? = recording.watched ? recordingStrings.watched : recording.inProgress ? "Started" : nil
            let parts = [endedEarly, watch].compactMap { $0 }
            return parts.isEmpty ? nil : parts.joined(separator: " · ")
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
                Button(recordingStrings.takeLabel(rule: rule)) {
                    change { try await model.core.updateRule(rule: rule, newOnly: !rule.newOnly, keep: rule.keep) }
                }
                Button(recordingStrings.keepLabel(rule: rule)) {
                    change { try await model.core.updateRule(rule: rule, newOnly: rule.newOnly, keep: model.core.nextKeep(keep: rule.keep)) }
                }
                Button(recordingStrings.seriesStop, role: .destructive) { stopping = true }
            }
        }
        .confirmationDialog("Stop recording \(rule.title)? What it recorded so far is kept.", isPresented: $stopping, titleVisibility: .visible) {
            Button(recordingStrings.seriesStop, role: .destructive) { change { try await model.core.deleteRule(rule: rule) } }
            Button(recordingStrings.keepIt, role: .cancel) {}
        }
    }

    /// "New episodes only · Keeps 5 · Any channel"
    private var summary: String {
        recordingStrings.summary(rule: rule)
    }

    private func change(_ action: @escaping () async throws -> KotlinBoolean) {
        Task {
            if !((try? await action())?.boolValue ?? false) { refused() }
        }
    }
}

extension RecordingGroup {
    var title: String {
        recordingStrings.groupTitle(group: self)
    }
}

extension DvrRecording: @retroactive Identifiable {}

extension DvrRecording {
    /// What ending it is called: a running one stops, a scheduled one is canceled, anything else is deleted.
    var deleteLabel: String {
        recordingStrings.deleteLabel(state: state)
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

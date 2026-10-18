import SwiftUI
import SofaKingCore

/// What is on the channel now, in full, and what follows it, with Watch and the favorite toggle, and on a server
/// that records, Record and Record series for each.
struct ProgramDetailView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    let channel: Channel
    let watch: () -> Void

    var body: some View {
        let nowNext = model.core.nowAndNext(lineup: lineup, channel: channel)
        ScrollView {
            HStack(alignment: .top, spacing: 48) {
                if let icon = nowNext.now?.icon, let url = URL(string: icon) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFit()
                    } placeholder: {
                        Color.clear
                    }
                    .frame(width: 400, height: 300)
                    .clipShape(.rect(cornerRadius: 16))
                }
                VStack(alignment: .leading, spacing: 20) {
                    Text("\(channel.number.formatted()) \(channel.name)").font(.headline).foregroundStyle(.secondary)
                    if let now = nowNext.now {
                        Text(now.title).font(.title)
                        if let line = episodeLine(now) {
                            Text(line).foregroundStyle(.secondary)
                        }
                        Text(airing(now)).foregroundStyle(.secondary)
                        if let summary = model.core.summary(programme: now), !summary.isEmpty {
                            Text(summary).font(.body)
                        }
                        RecordButtons(programme: now)
                    } else {
                        Text("No program information").font(.title2)
                    }
                    if let next = nowNext.next {
                        Divider()
                        Text("Next").font(.headline)
                        Text("\(next.title) at \(model.core.startTime(of: next))").foregroundStyle(.secondary)
                        if let line = episodeLine(next) {
                            Text(line).font(.caption).foregroundStyle(.tertiary)
                        }
                        RecordButtons(programme: next)
                    }
                    HStack(spacing: 24) {
                        Button("Watch", systemImage: "play.fill", action: watch)
                        FavoriteButton(channel: channel)
                    }
                    .padding(.top, 16)
                }
                Spacer()
            }
            .padding(48)
        }
        .navigationTitle(channel.name)
    }

    /// The episode title, its number, a first-airing mark and the rating, those that the guide gave.
    private func episodeLine(_ programme: Programme) -> String? {
        var parts: [String] = []
        if let subTitle = programme.subTitle, !subTitle.isEmpty { parts.append(subTitle) }
        if let episode = programme.episode, !episode.isEmpty { parts.append(episode) }
        if programme.isNew { parts.append("New") }
        if let rating = programme.rating, !rating.isEmpty { parts.append(rating) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// `7:00 – 8:00 PM · 25 min left`
    private func airing(_ programme: Programme) -> String {
        let start = model.core.start(of: programme)
        let end = model.core.end(of: programme)
        let range = (start..<end).formatted(.interval.hour().minute())
        let left = max(0, Int(end.timeIntervalSinceNow / 60))
        return "\(range) · \(left) min left"
    }
}

/// Record (Cancel recording once set) and Record series (Stop series once set) for a program that has not ended,
/// on a server that records; a line says when the server did not take the change.
private struct RecordButtons: View {
    @Environment(AppModel.self) private var model
    let programme: Programme
    @State private var busy = false
    @State private var failed = false

    var body: some View {
        if model.recordingsShown, model.core.recordable(programme: programme), let marks = model.recordingMarks {
            let mark = model.core.recordingMark(marks: marks, programme: programme)
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 24) {
                    Button(mark.recording ? "Cancel recording" : "Record", systemImage: mark.recording ? "xmark.circle" : "record.circle") {
                        change { try await model.core.toggleRecording(programme: programme) }
                    }
                    Button(mark.series ? "Stop series" : "Record series", systemImage: mark.series ? "xmark.circle" : "repeat.circle") {
                        change { try await model.core.toggleSeries(programme: programme) }
                    }
                }
                if failed {
                    Text("The server did not take that change. Try again.").font(.caption).foregroundStyle(.red)
                }
            }
        }
    }

    private func change(_ action: @escaping () async throws -> KotlinBoolean) {
        guard !busy else { return }
        busy = true
        Task {
            failed = !((try? await action())?.boolValue ?? false)
            busy = false
        }
    }
}

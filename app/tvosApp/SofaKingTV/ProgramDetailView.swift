import SwiftUI
import SofaKingCore

/// What is on the channel now, in full, and what follows it, with Watch and the favorite toggle, and on a server
/// that records, Record and Record series for each. More airings lists the program's other airings on any channel:
/// one on now plays its channel, and a later one opens its details, which show that airing in place of now and next.
struct ProgramDetailView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    let channel: Channel
    /// A later airing to show in place of what is on now.
    var airing: Programme? = nil
    let watch: () -> Void
    @State private var opened: Airing?

    var body: some View {
        let nowNext = model.core.nowAndNext(lineup: lineup, channel: channel)
        let shown = airing ?? nowNext.now
        ScrollView {
            HStack(alignment: .top, spacing: 48) {
                if let icon = shown?.icon, let url = URL(string: icon) {
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
                    if let shown {
                        Text(shown.title).font(.title)
                        if let line = episodeLine(shown) {
                            Text(line).foregroundStyle(.secondary)
                        }
                        Text(timing(shown)).foregroundStyle(.secondary)
                        if let summary = model.core.summary(programme: shown), !summary.isEmpty {
                            Text(summary).font(.body)
                        }
                        RecordButtons(programme: shown)
                    } else {
                        Text("No program information").font(.title2)
                    }
                    if airing == nil, let next = nowNext.next {
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
                    if let shown {
                        MoreAirings(airings: model.core.airings(lineup: lineup, programme: shown)) { other in
                            if model.core.start(of: other.programme) <= .now {
                                model.requestedChannel = other.channel.guideId
                            } else {
                                opened = other
                            }
                        }
                    }
                }
                Spacer()
            }
            .padding(48)
        }
        .navigationTitle(channel.name)
        .navigationDestination(item: $opened) { other in
            ProgramDetailView(lineup: lineup, channel: other.channel, airing: other.programme) {
                model.requestedChannel = other.channel.guideId
            }
        }
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

    /// `7:00 – 8:00 PM · 25 min left`, or for a later airing its day and times.
    private func timing(_ programme: Programme) -> String {
        let start = model.core.start(of: programme)
        let end = model.core.end(of: programme)
        if start > .now { return (start..<end).formatted(.interval.weekday().hour().minute()) }
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
                    Text("The server didn't take that change. Try again.").font(.caption).foregroundStyle(.red)
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

/// The program's other airings, soonest first, each with its day, time and channel; a press hands it to [pick].
private struct MoreAirings: View {
    @Environment(AppModel.self) private var model
    let airings: [Airing]
    let pick: (Airing) -> Void

    var body: some View {
        if !airings.isEmpty {
            Divider()
            Text("More airings").font(.headline)
            ForEach(airings, id: \.self) { other in
                Button { pick(other) } label: {
                    HStack(spacing: 24) {
                        Text(when(other.programme)).monospacedDigit()
                        Text("\(other.channel.number.formatted()) \(other.channel.name)").foregroundStyle(.secondary)
                        if other.programme.isNew { Text("New").font(.caption).foregroundStyle(.secondary) }
                    }
                }
            }
        }
    }

    /// "On now", or the day and time it starts.
    private func when(_ programme: Programme) -> String {
        let start = model.core.start(of: programme)
        if start <= .now { return "On now" }
        return start.formatted(.dateTime.weekday().hour().minute())
    }
}

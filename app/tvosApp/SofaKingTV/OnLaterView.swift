import SwiftUI
import SofaKingCore

/// On Later: the movies, sports, kids' programs or premieres coming up today and tomorrow on the channels the list
/// shows, soonest first, each with its day, time and channel. Selecting one opens its details, where Record, Record
/// series, Add to watchlist and Watch work as from the channel list.
struct OnLaterView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    @State private var kind = OnLaterKind.movies
    /// What the server listed for `kind`; nil while it loads.
    @State private var airings: [Airing]?
    @State private var failed = false
    @State private var attempt = 0
    @State private var opened: Airing?

    private static let kinds: [(OnLaterKind, String)] = [
        (.movies, "Movies"), (.sports, "Sports"), (.kids, "Kids"), (.premieres, "Premieres"),
    ]

    var body: some View {
        List {
            Picker("Show", selection: $kind) {
                ForEach(Self.kinds, id: \.0) { entry in Text(entry.1).tag(entry.0) }
            }
            .pickerStyle(.segmented)
            if failed {
                Text("On later could not be loaded.").foregroundStyle(.secondary)
                Button("Try again") { attempt += 1 }
            } else if let airings {
                if airings.isEmpty {
                    Text("Nothing like this is on in the next two days.").foregroundStyle(.secondary)
                }
                ForEach(airings, id: \.self) { airing in
                    Button { opened = airing } label: { row(airing) }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("On later")
        .task(id: "\(kind.name) \(attempt)") {
            airings = nil
            failed = false
            do {
                let found = try await model.core.onLater(kind: kind, lineup: lineup)
                if let found { airings = found } else { failed = true }
            } catch {
                failed = true
            }
        }
        .navigationDestination(item: $opened) { airing in
            ProgramDetailView(lineup: lineup, channel: airing.channel, airing: airing.program) {
                model.requestedChannel = airing.channel.guideId
            }
        }
    }

    private func row(_ airing: Airing) -> some View {
        HStack(spacing: 24) {
            VStack(alignment: .leading, spacing: 4) {
                Text(airing.program.title).font(.headline)
                Text("\(when(airing.program)) · \(airing.channel.number.formatted()) \(airing.channel.name)")
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if airing.program.isNew { Text("New").font(.caption).foregroundStyle(.secondary) }
        }
    }

    /// "On now", or the day and time it starts.
    private func when(_ program: Program) -> String {
        let start = model.core.start(of: program)
        if start <= .now { return "On now" }
        return start.formatted(.dateTime.weekday().hour().minute())
    }
}

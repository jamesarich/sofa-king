import SwiftUI
import SofaKingCore

/// Every channel with the program on now and next, favorites first; selecting one plays it full screen, and a long
/// press (or the play/pause button's context menu) opens the program's details or stars it. A cold start opens the
/// last watched channel, and a `sofaking://channel/<guideId>` link from the Top Shelf opens that one.
struct ChannelListView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    @State private var playing: Channel?
    @State private var detail: Channel?
    @State private var query = ""

    private var favorites: Set<String> { model.favorites }

    private var shown: [Channel] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return lineup.channels }
        return lineup.channels.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed) || $0.number.formatted().hasPrefix(trimmed)
        }
    }

    var body: some View {
        List {
            let starred = shown.filter { favorites.contains($0.guideId) }
            if !starred.isEmpty {
                Section("Favorites") { rows(starred) }
            }
            Section(starred.isEmpty ? "" : "All channels") { rows(shown) }
        }
        .searchable(text: $query, prompt: "Channel name or number")
        .navigationTitle("Sofa King")
        .navigationDestination(item: $detail) { channel in
            ProgramDetailView(lineup: lineup, channel: channel) { playing = channel }
        }
        .fullScreenCover(item: $playing) { channel in
            PlayerView(channel: channel)
        }
        .onAppear {
            // Once per process, as the Compose app's launch decision: the last channel opens full screen.
            guard !model.launchDecided else { return }
            model.launchDecided = true
            if let last = model.settings?.lastChannel, let channel = lineup.channels.first(where: { $0.guideId == last }) {
                playing = channel
            }
        }
        .onChange(of: model.requestedChannel, initial: true) { _, requested in
            // The player, when one is up, takes the request instead and switches to the channel.
            guard playing == nil, let requested else { return }
            model.requestedChannel = nil
            if let channel = lineup.channels.first(where: { $0.guideId == requested }) { playing = channel }
        }
    }

    @ViewBuilder
    private func rows(_ channels: [Channel]) -> some View {
        ForEach(channels, id: \.guideId) { channel in
            Button { playing = channel } label: { ChannelRow(lineup: lineup, channel: channel, favorite: favorites.contains(channel.guideId)) }
                .contextMenu {
                    Button("Program details", systemImage: "info.circle") { detail = channel }
                    FavoriteButton(channel: channel)
                }
        }
    }
}

/// Adds or removes the channel from the favorites, worded for its current state.
struct FavoriteButton: View {
    @Environment(AppModel.self) private var model
    let channel: Channel

    var body: some View {
        let favorite = model.favorites.contains(channel.guideId)
        Button(favorite ? "Remove from favorites" : "Add to favorites", systemImage: favorite ? "star.slash" : "star") {
            model.core.toggleFavorite(guideId: channel.guideId)
        }
    }
}

private struct ChannelRow: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    let channel: Channel
    let favorite: Bool

    var body: some View {
        let nowNext = model.core.nowAndNext(lineup: lineup, channel: channel)
        HStack(spacing: 24) {
            Text(channel.number.formatted()).monospacedDigit().frame(width: 120, alignment: .trailing)
            VStack(alignment: .leading, spacing: 4) {
                Text(channel.name).font(.headline)
                if let now = nowNext.now {
                    Text(now.title).foregroundStyle(.secondary)
                }
                if let next = nowNext.next {
                    Text("Next: \(next.title) at \(model.core.startTime(of: next))").font(.caption).foregroundStyle(.tertiary)
                }
            }
            if favorite {
                Spacer()
                Image(systemName: "star.fill").foregroundStyle(.yellow).accessibilityLabel("Favorite")
            }
        }
    }
}

extension Channel: @retroactive Identifiable {
    public var id: String { guideId }
}

extension SofaKingTv {
    func startTime(of programme: Programme) -> String {
        start(of: programme).formatted(date: .omitted, time: .shortened)
    }

    func start(of programme: Programme) -> Date { Date(timeIntervalSince1970: Double(epochMs(programme: programme)) / 1000) }

    func end(of programme: Programme) -> Date { Date(timeIntervalSince1970: Double(endEpochMs(programme: programme)) / 1000) }
}

import SwiftUI
import SofaKingCore

/// Every channel with the program on now and next, favorites first; selecting one plays it full screen, and a long
/// press (or the play/pause button's context menu) stars it. A cold start opens the last watched channel.
struct ChannelListView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    @State private var playing: Channel?
    @State private var query = ""

    private var favorites: Set<AnyHashable> { model.settings?.favorites ?? [] }

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
        .toolbar {
            NavigationLink { SettingsView() } label: { Label("Settings", systemImage: "gear").labelStyle(.iconOnly) }
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
    }

    @ViewBuilder
    private func rows(_ channels: [Channel]) -> some View {
        ForEach(channels, id: \.guideId) { channel in
            Button { playing = channel } label: { ChannelRow(lineup: lineup, channel: channel, favorite: favorites.contains(channel.guideId)) }
                .contextMenu {
                    Button(favorites.contains(channel.guideId) ? "Remove from favorites" : "Add to favorites") {
                        model.core.toggleFavorite(guideId: channel.guideId)
                    }
                }
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
                    Text("Next: \(next.title) at \(time(of: next))").font(.caption).foregroundStyle(.tertiary)
                }
            }
            if favorite {
                Spacer()
                Image(systemName: "star.fill").foregroundStyle(.yellow).accessibilityLabel("Favorite")
            }
        }
    }

    private func time(of programme: Programme) -> String {
        Date(timeIntervalSince1970: Double(model.core.epochMs(programme: programme)) / 1000)
            .formatted(date: .omitted, time: .shortened)
    }
}

extension Channel: @retroactive Identifiable {
    public var id: String { guideId }
}

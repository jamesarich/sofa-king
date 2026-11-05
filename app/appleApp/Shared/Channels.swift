import SwiftUI
import SofaKingCore

/// Which channels the Live tab shows, as the other apps' first chips choose them.
enum ChannelFilter: Hashable { case all, favorites, recent }

extension AppModel {
    func channels(_ filter: ChannelFilter, of lineup: Lineup) -> [Channel] {
        switch filter {
        case .all: return lineup.channels
        case .favorites:
            // In the order the viewer starred them, as the Favorites filter keeps it everywhere.
            let byId = Dictionary(lineup.channels.map { ($0.guideId, $0) }, uniquingKeysWith: { first, _ in first })
            return (settings?.favorites ?? []).compactMap { ($0 as? String).flatMap { byId[$0] } }
        case .recent: return recentChannels
        }
    }
}

/// Finds a channel by name or number, with what is on it now; selecting one plays it full screen. It is a page of its
/// own because tvOS draws a searchable view as a search screen, keyboard on top, which would hold the guide under it.
struct ChannelSearchView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    let play: (Channel) -> Void
    @State private var query = ""

    private var matches: [Channel] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return lineup.channels }
        return lineup.channels.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed) || $0.number.formatted().hasPrefix(trimmed)
        }
    }

    var body: some View {
        List(matches) { channel in
            Button { play(channel) } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(channel.number.formatted()) \(channel.name)")
                    if let now = model.core.nowAndNext(lineup: lineup, channel: channel).now {
                        Text(now.title).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .overlay {
            if matches.isEmpty { Text(strings.noChannelMatches).foregroundStyle(.secondary) }
        }
        .searchable(text: $query, prompt: "Channel name or number")
        .navigationTitle(strings.search)
    }
}

/// Adds or removes the channel from the favorites, worded for its current state.
struct FavoriteButton: View {
    @Environment(AppModel.self) private var model
    let channel: Channel

    var body: some View {
        let favorite = model.favorites.contains(channel.guideId)
        Button(favorite ? strings.favoriteRemove : strings.favoriteAdd, systemImage: favorite ? "star.slash" : "star") {
            model.core.toggleFavorite(guideId: channel.guideId)
        }
    }
}

extension Channel: @retroactive Identifiable {
    public var id: String { guideId }
}

extension SofaKingTv {
    func startTime(of program: Program) -> String {
        start(of: program).formatted(date: .omitted, time: .shortened)
    }

    func start(of program: Program) -> Date { Date(timeIntervalSince1970: Double(epochMs(program: program)) / 1000) }

    func end(of program: Program) -> Date { Date(timeIntervalSince1970: Double(endEpochMs(program: program)) / 1000) }
}

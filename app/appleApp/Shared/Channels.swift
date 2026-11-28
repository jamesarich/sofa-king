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

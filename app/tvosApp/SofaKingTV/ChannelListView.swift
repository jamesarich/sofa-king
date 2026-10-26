import SwiftUI
import SofaKingCore

/// The Live tab: the guide grid under All, Favorites and Recent, and On later on a server that offers it, with
/// search by channel name or number. Selecting a program on now plays its channel full screen, and Menu from the
/// player comes back to the guide on the channel just watched; a later program, or a long press (or the play/pause
/// button's context menu) on any, opens its details. A cold start opens the last watched channel, and a
/// `sofaking://channel/<guideId>` link from the Top Shelf opens that one.
struct ChannelListView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    @State private var playing: Channel?
    @State private var detail: ProgramChoice?
    @State private var query = ""
    @State private var filter = ChannelFilter.all
    /// The channel the guide focuses next: the last one at first, the one just watched after the player.
    @State private var arrival: String?

    /// Which channels the guide shows, as the other apps' first chips choose them.
    enum ChannelFilter: Hashable { case all, favorites, recent }

    /// A program's details to open: the channel's on now and next, or one later program.
    struct ProgramChoice: Hashable {
        let channel: Channel
        let program: Program?
    }

    private var filtered: [Channel] {
        switch filter {
        case .all: return lineup.channels
        case .favorites:
            // In the order the viewer starred them, as the Favorites filter keeps it everywhere.
            let byId = Dictionary(lineup.channels.map { ($0.guideId, $0) }, uniquingKeysWith: { first, _ in first })
            return (model.settings?.favorites ?? []).compactMap { ($0 as? String).flatMap { byId[$0] } }
        case .recent: return model.recentChannels
        }
    }

    private var shown: [Channel] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return filtered }
        return filtered.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed) || $0.number.formatted().hasPrefix(trimmed)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack(spacing: 32) {
                Picker("Channels", selection: $filter) {
                    Text("All").tag(ChannelFilter.all)
                    Text("Favorites").tag(ChannelFilter.favorites)
                    if !model.recentChannels.isEmpty { Text("Recent").tag(ChannelFilter.recent) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 720)
                if model.capabilities?.onLater == true {
                    NavigationLink {
                        OnLaterView(lineup: lineup)
                    } label: {
                        Label("On later", systemImage: "calendar")
                    }
                }
            }
            let channels = shown
            if channels.isEmpty {
                Text(emptyText).foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                GuideGrid(lineup: lineup, channels: channels, arrival: $arrival) { channel in
                    playing = channel
                } details: { channel, program in
                    detail = ProgramChoice(channel: channel, program: program)
                }
            }
        }
        .searchable(text: $query, prompt: "Channel name or number")
        .navigationTitle(model.settings?.kidsMode == true ? "Sofa King Kids" : "Sofa King")
        .navigationDestination(item: $detail) { choice in
            ProgramDetailView(lineup: lineup, channel: choice.channel, airing: choice.program) { playing = choice.channel }
        }
        .fullScreenCover(item: $playing, onDismiss: returnFromPlayer) { channel in
            PlayerView(channel: channel)
        }
        .onAppear {
            // Once per process, as the Compose app's launch decision: the last channel (or the chosen one) opens
            // full screen, unless the start opens Library or DVR, where nothing plays by itself.
            guard !model.launchDecided else { return }
            model.launchDecided = true
            arrival = model.settings?.lastChannel ?? lineup.channels.first?.guideId
            guard let settings = model.settings, model.startTab == nil, settings.startup != .guide else { return }
            let wanted = settings.startup == .channel ? settings.startupChannel : settings.lastChannel
            if let wanted, let channel = lineup.channels.first(where: { $0.guideId == wanted }) {
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

    private var emptyText: String {
        if !query.trimmingCharacters(in: .whitespaces).isEmpty { return "No channel matches." }
        return filter == .favorites
            ? "No favorites yet. Hold the touch surface on a program and choose Add to favorites."
            : "No channels."
    }

    /// Back from the player focuses the channel watched, the last one switched to included; a filter or search that
    /// hides it gives way to All, as on the other apps.
    private func returnFromPlayer() {
        guard let watched = model.settings?.lastChannel else { return }
        if !shown.contains(where: { $0.guideId == watched }) {
            filter = .all
            query = ""
        }
        arrival = watched
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

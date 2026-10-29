import SwiftUI
import SofaKingCore

/// The Live tab: the guide grid under All, Favorites and Recent, On later on a server that offers it, and Search,
/// a page that finds a channel by name or number. Selecting a program on now plays its channel full screen, and Menu from the
/// player comes back to the guide on the channel just watched; a later program, or a long press (or the play/pause
/// button's context menu) on any, opens its details. A cold start opens the last watched channel, and a
/// `sofaking://channel/<guideId>` link from the Top Shelf opens that one.
struct ChannelListView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    let lineup: Lineup
    @State private var preview = GuidePreview()
    /// Whether the guide is the page on top of the Live tab.
    @State private var visible = false
    @State private var playing: Channel?
    @State private var detail: ProgramChoice?
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

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            PreviewBand(lineup: lineup, preview: preview)
            HStack(spacing: 32) {
                Picker(strings.channelsSection, selection: $filter) {
                    Text(strings.filterAll).tag(ChannelFilter.all)
                    Text(strings.filterFavorites).tag(ChannelFilter.favorites)
                    if !model.recentChannels.isEmpty { Text(strings.filterRecent).tag(ChannelFilter.recent) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 720)
                NavigationLink {
                    ChannelSearchView(lineup: lineup) { playing = $0 }
                } label: {
                    Label(strings.search, systemImage: "magnifyingglass")
                }
                if model.capabilities?.onLater == true {
                    NavigationLink {
                        OnLaterView(lineup: lineup)
                    } label: {
                        Label(strings.onLater, systemImage: "calendar")
                    }
                }
            }
            let channels = filtered
            if channels.isEmpty {
                Text(emptyText).foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                GuideGrid(
                    lineup: lineup,
                    channels: channels,
                    arrival: $arrival,
                    play: { playing = $0 },
                    details: { channel, program in detail = ProgramChoice(channel: channel, program: program) },
                    onFocus: { channel, program in preview.focus(channel?.guideId, program: program) }
                )
            }
        }
        .navigationTitle(model.settings?.kidsMode == true ? "Sofa King Kids" : strings.appName)
        .navigationDestination(item: $detail) { choice in
            ProgramDetailView(lineup: lineup, channel: choice.channel, airing: choice.program) { playing = choice.channel }
        }
        .fullScreenCover(item: $playing, onDismiss: returnFromPlayer) { channel in
            PlayerView(channel: channel)
        }
        .onAppear {
            visible = true
            preview.openFullScreen = { id in playing = lineup.channels.first { $0.guideId == id } }
            if playing == nil { preview.activate(model) }
        }
        .onDisappear {
            visible = false
            preview.deactivate(handoff: playing != nil)
        }
        .onChange(of: playing) { _, now in
            // The full screen takes the preview's stream and session over as they are.
            if now != nil { preview.deactivate(handoff: true) }
        }
        .onChange(of: scenePhase) { _, phase in
            guard visible, playing == nil else { return }
            if phase == .background { preview.deactivate(handoff: false) } else if phase == .active { preview.activate(model) }
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
        return filter == .favorites
            ? "No favorites yet. Hold the touch surface on a program and choose Add to favorites."
            : strings.noChannels
    }

    /// Back from the player focuses the channel watched, the last one switched to included; a filter that hides it
    /// gives way to All, as on the other apps.
    private func returnFromPlayer() {
        if visible { preview.activate(model) }
        guard let watched = model.settings?.lastChannel else { return }
        preview.suppressed = watched
        if !filtered.contains(where: { $0.guideId == watched }) { filter = .all }
        arrival = watched
    }
}

/// Finds a channel by name or number, with what is on it now; selecting one plays it full screen. It is a page of its
/// own because tvOS draws a searchable view as a search screen, keyboard on top, which would hold the guide under it.
private struct ChannelSearchView: View {
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

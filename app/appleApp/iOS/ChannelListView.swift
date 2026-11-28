import SwiftUI
import SofaKingCore

/// The Live tab on a phone or tablet, under All, Favorites and Recent, with a search field that filters the channels,
/// Search (the page that finds channels, programs and library titles) and On later on a server that offers it: on an
/// iPad in the regular width, the Apple TV's guide grid, where a tap on a program on now plays its channel and a later
/// one opens its details; on a phone or a narrow window, the channels with what is on each now, where a tap plays the
/// channel. Full screen either way; a long press opens the details or stars the channel. A cold start opens the last
/// watched channel, and a `sofaking://live` link opens that one. The guide's preview is the Apple TV's.
struct ChannelListView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    let lineup: Lineup
    @State private var playing: Channel?
    @State private var detail: ProgramChoice?
    @State private var filter = ChannelFilter.all
    @State private var query = ""
    @State private var searching = false
    /// The channel the grid scrolls to; nothing asks for one on a tablet yet.
    @State private var arrival: String?

    /// A program's details to open: the channel's on now.
    struct ProgramChoice: Hashable {
        let channel: Channel
        let program: Program?
    }

    private var channels: [Channel] {
        let all = model.channels(filter, of: lineup)
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return all }
        return all.filter { $0.name.localizedCaseInsensitiveContains(text) || $0.number.formatted().hasPrefix(text) }
    }

    /// The grid needs a tablet's width: a phone in landscape, even one in the regular width, keeps the list.
    private var showsGrid: Bool {
        sizeClass == .regular && UIDevice.current.userInterfaceIdiom == .pad
    }

    private var emptyText: String {
        filter == .favorites ? strings.favoritesNone : (query.isEmpty ? strings.noChannels : strings.noChannelMatches)
    }

    private var filterPicker: some View {
        Picker(strings.channelsSection, selection: $filter) {
            Text(strings.filterAll).tag(ChannelFilter.all)
            Text(strings.filterFavorites).tag(ChannelFilter.favorites)
            if !model.recentChannels.isEmpty { Text(strings.filterRecent).tag(ChannelFilter.recent) }
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("channel-filter")
    }

    var body: some View {
        Group {
            if showsGrid { grid } else { list }
        }
        .searchable(text: $query, isPresented: $searching, prompt: "Channel name or number")
        .navigationTitle(model.settings?.kidsMode == true ? "Sofa King Kids" : strings.appName)
        .toolbar {
            NavigationLink {
                SearchView(opensItemsHere: false)
            } label: {
                Label(searchStrings.search, systemImage: "magnifyingglass")
            }
            .accessibilityIdentifier("search")
            if model.capabilities?.onLater == true {
                NavigationLink {
                    OnLaterView(lineup: lineup)
                } label: {
                    Label(strings.onLater, systemImage: "calendar")
                }
            }
        }
        .navigationDestination(item: $detail) { choice in
            ProgramDetailView(lineup: lineup, channel: choice.channel, airing: choice.program) { playing = choice.channel }
        }
        .fullScreenCover(item: $playing) { channel in
            PlayerView(channel: channel)
        }
        .onChange(of: playing) { _, now in
            // A search field still focused under the player brings its keyboard up over the picture when the player's
            // menu opens, and taps then type into the search behind it, so a channel that plays ends the search.
            if now != nil { searching = false }
        }
        .background(Color.sofaPlum.ignoresSafeArea())
        .scrollContentBackground(.hidden)
        .onAppear {
            // Once per process, as the Compose app's launch decision: the last channel (or the chosen one) opens
            // full screen, unless the start opens Library or DVR, where nothing plays by itself.
            guard !model.launchDecided else { return }
            model.launchDecided = true
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

    private var grid: some View {
        VStack(alignment: .leading, spacing: 12) {
            filterPicker.frame(maxWidth: 480)
            let shown = channels
            if shown.isEmpty {
                Text(emptyText).foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                GuideGrid(
                    lineup: lineup,
                    channels: shown,
                    arrival: $arrival,
                    play: { playing = $0 },
                    details: { channel, program in detail = ProgramChoice(channel: channel, program: program) }
                )
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("channel-list")
            }
        }
        .padding(.horizontal, 20)
    }

    private var list: some View {
        List {
            filterPicker.listRowBackground(Color.clear)
            ForEach(channels) { channel in
                Button { playing = channel } label: {
                    ChannelRow(channel: channel, lineup: lineup, playing: false)
                }
                .listRowBackground(Color.sofaPlum)
                .accessibilityIdentifier("channel-\(channel.name)")
                .contextMenu {
                    Button("Details", systemImage: "info.circle") {
                        detail = ProgramChoice(channel: channel, program: model.core.nowAndNext(lineup: lineup, channel: channel).now)
                    }
                    FavoriteButton(channel: channel)
                }
            }
        }
        .listStyle(.plain)
        .accessibilityIdentifier("channel-list")
        .overlay {
            if channels.isEmpty {
                Text(emptyText).foregroundStyle(.secondary)
            }
        }
    }
}

/// A channel's logo, number and name over what is on it now.
struct ChannelRow: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let lineup: Lineup
    let playing: Bool

    var body: some View {
        let now = model.core.nowAndNext(lineup: lineup, channel: channel).now
        HStack(spacing: 12) {
            AsyncImage(url: channel.logo.flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFit()
            } placeholder: {
                Color.clear
            }
            .frame(width: 56, height: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(channel.number.formatted()) \(channel.name)").font(.headline).foregroundStyle(Color.sofaDusk)
                if let now {
                    Text(now.title).font(.subheadline).foregroundStyle(Color.sofaMauve).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if playing { Image(systemName: "speaker.wave.2.fill").foregroundStyle(Color.sofaAmber) }
        }
        .contentShape(.rect)
    }
}

import SwiftUI
import SofaKingCore

/// The Live tab on a phone or tablet: the channels under All, Favorites and Recent, each with what is on it now, and a
/// search field and On later on a server that offers it. A tap plays the channel full screen; a long press opens its
/// details or stars it. A cold start opens the last watched channel, and a `sofaking://live` link opens that one.
/// The guide grid and its preview are the Apple TV's.
struct ChannelListView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    @State private var playing: Channel?
    @State private var detail: ProgramChoice?
    @State private var filter = ChannelFilter.all
    @State private var query = ""

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

    var body: some View {
        List {
            Picker(strings.channelsSection, selection: $filter) {
                Text(strings.filterAll).tag(ChannelFilter.all)
                Text(strings.filterFavorites).tag(ChannelFilter.favorites)
                if !model.recentChannels.isEmpty { Text(strings.filterRecent).tag(ChannelFilter.recent) }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .accessibilityIdentifier("channel-filter")
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
                Text(filter == .favorites ? strings.favoritesNone : (query.isEmpty ? strings.noChannels : strings.noChannelMatches))
                    .foregroundStyle(.secondary)
            }
        }
        .searchable(text: $query, prompt: "Channel name or number")
        .navigationTitle(model.settings?.kidsMode == true ? "Sofa King Kids" : strings.appName)
        .toolbar {
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

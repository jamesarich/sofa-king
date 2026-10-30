import SwiftUI
import SofaKingCore

/// A library item's page, pushed by id from a `sofaking://item` link.
struct ItemRoute: Hashable {
    let id: String
}

/// The Library tab: a spotlight on the focused Continue watching or Recently added card, then those shelves, each
/// library's recent additions and the libraries themselves. Selecting a Continue watching card resumes it; any other
/// card opens its page. The remote's play/pause button plays a movie or episode straight from its card, and holding the
/// touch surface on a Continue watching card offers Details, which opens its page, and Remove from Continue watching.
/// While music plays, Now playing heads the page and opens the song's page again. Search above the page opens
/// `LibrarySearchView`.
struct LibraryHomeView: View {
    @Environment(AppModel.self) private var model
    @State private var path = NavigationPath()
    @FocusState private var focusedId: String?
    /// The last spotlight card focus rested on, so the spotlight keeps its item while focus is on another shelf.
    @State private var spotlightId: String?

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $path) {
            content
                .navigationDestination(for: MediaItem.self) { ItemDetailView(itemId: $0.id) }
                .navigationDestination(for: ItemRoute.self) { ItemDetailView(itemId: $0.id) }
                .navigationDestination(for: MediaLibrary.self) { LibraryGridView(library: $0) }
                .librarySearchButton(shown: model.libraryHome is LibraryHomeStateReady)
        }
        .fullScreenCover(item: $model.vod) { request in
            // Each request is its own player, so Up next's swap starts the next item on fresh state and the one it
            // leaves reports its stop as itself.
            VodPlayerView(request: request).id(request)
        }
        .onAppear {
            model.openRequestedItem()
            openRequestedDetails()
        }
        .onChange(of: model.requestedDetails) { openRequestedDetails() }
        .onChange(of: model.requestedItem) { model.openRequestedItem() }
        .onChange(of: model.libraryHome is LibraryHomeStateReady) { model.openRequestedItem() }
    }

    /// Shows the page a `sofaking://item/<id>` link named, in place of any page already open over the Library.
    private func openRequestedDetails() {
        guard let id = model.requestedDetails else { return }
        model.requestedDetails = nil
        path = NavigationPath()
        path.append(ItemRoute(id: id))
    }

    @ViewBuilder
    private var content: some View {
        switch model.libraryHome {
        case let ready as LibraryHomeStateReady:
            if ready.home.shelves.isEmpty && ready.home.libraries.isEmpty {
                ContentUnavailableView(
                    strings.libraryEmpty,
                    systemImage: "film.stack",
                    description: Text("The server adds what it finds on its next library scan.")
                )
            } else {
                shelves(ready.home)
            }
        case let failed as LibraryHomeStateFailed:
            VStack(spacing: 32) {
                Text(strings.libraryLoadFailed).font(.title2)
                Text(failed.message).foregroundStyle(.secondary)
                Button(strings.retry) { model.core.refreshLibrary() }
            }
        case is LibraryHomeStateUnavailable:
            ContentUnavailableView(
                "This server has no libraries",
                systemImage: "film.stack",
                description: Text("Add a movies or shows folder in the admin portal, under Library.")
            )
        default:
            ProgressView("Sofa King close, loading the library…")
        }
    }

    private func shelves(_ home: MediaHome) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 48) {
                // It only draws the focused card; focus stays on the cards, and where it starts is unchanged.
                if let shown = SpotlightChoicesKt.spotlightItem(shelves: home.shelves, focusedId: spotlightId) {
                    LibrarySpotlight(item: shown)
                }
                if let song = model.music.song {
                    Button { model.nowPlayingShown = true } label: {
                        Label("Now playing: \(song.title)", systemImage: "music.note").lineLimit(1)
                    }
                }
                ForEach(home.shelves, id: \.key) { shelf in
                    let wide = shelf.isWide
                    Section {
                        CardRow {
                            ForEach(shelf.items) { item in
                                card(item, in: shelf, wide: wide)
                                    .playsOnPlayPause(item)
                                    .contextMenu {
                                        if shelf.id == LibraryChoicesKt.CONTINUE_SHELF {
                                            Button(strings.libraryDetails, systemImage: "info.circle") { path.append(item) }
                                            Button(strings.libraryRemoveFromContinue, systemImage: "minus.circle") {
                                                model.core.removeFromContinue(item: item)
                                            }
                                        }
                                    }
                            }
                        }
                    } header: {
                        Text(shelf.title).font(.title3)
                    }
                }
                if !home.libraries.isEmpty {
                    Section {
                        CardRow {
                            ForEach(home.libraries) { library in
                                NavigationLink(value: library) { LibraryTile(library: library) }
                                    .buttonStyle(.card)
                            }
                        }
                    } header: {
                        Text("Libraries").font(.title3)
                    }
                }
            }
        }
        .onChange(of: focusedId) { _, id in
            if let id { spotlightId = id }
        }
    }

    /// A card that resumes on select where the shared rule says so, else one that opens the item's page.
    @ViewBuilder
    private func card(_ item: MediaItem, in shelf: MediaShelf, wide: Bool) -> some View {
        let feeds = SpotlightChoicesKt.feedsSpotlight(shelf: shelf)
        let play = SpotlightChoicesKt.cardOkFor(shelf: shelf, item: item) == .play
        switch (play, feeds) {
        case (true, true):
            playCard(item, wide: wide).focused($focusedId, equals: item.id)
        case (true, false):
            playCard(item, wide: wide)
        case (false, true):
            pageCard(item, wide: wide).focused($focusedId, equals: item.id)
        case (false, false):
            pageCard(item, wide: wide)
        }
    }

    private func playCard(_ item: MediaItem, wide: Bool) -> some View {
        Button { model.playFromCard(item) } label: { LibraryCard(item: item, wide: wide) }
            .buttonStyle(.card)
    }

    private func pageCard(_ item: MediaItem, wide: Bool) -> some View {
        NavigationLink(value: item) { LibraryCard(item: item, wide: wide) }
            .buttonStyle(.card)
    }
}

/// The backdrop, title, year or episode and a line of the overview of the focused card, above the shelves. It is not
/// focusable and VoiceOver skips it, as the focused card already says all of it.
private struct LibrarySpotlight: View {
    let item: MediaItem
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Color.sofaPlumRaised
            SpotlightContent(item: item)
                .id(item.id)
                .transition(.opacity)
        }
        .containerRelativeFrame(.vertical) { height, _ in height * 0.4 }
        .clipped()
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: item.id)
        .accessibilityHidden(true)
    }
}

private struct SpotlightContent: View {
    let item: MediaItem

    var body: some View {
        let art = item.backdrop ?? item.poster
        ZStack(alignment: .bottomLeading) {
            if let art {
                Color.black.overlay {
                    AsyncImage(url: URL(string: art)) { phase in
                        if let image = phase.image {
                            // A poster stands in for a missing backdrop, blurred so its grain does not read as a picture.
                            if item.backdrop == nil {
                                image.resizable().scaledToFill().scaleEffect(1.1).blur(radius: 24)
                            } else {
                                image.resizable().scaledToFill()
                            }
                        }
                    }
                }
                .clipped()
                LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
                LinearGradient(colors: [.black.opacity(0.6), .clear], startPoint: .leading, endPoint: .trailing)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text(item.cardTitle).font(.largeTitle).bold().lineLimit(2)
                if let subtitle = item.cardSubtitle {
                    Text(subtitle).font(.headline).lineLimit(1)
                }
                if let tagline = SpotlightChoicesKt.spotlightTagline(item: item, limit: SpotlightChoicesKt.SPOTLIGHT_TAGLINE_LIMIT) {
                    Text(tagline).font(.body).lineLimit(2)
                }
            }
            .foregroundStyle(art == nil ? Color.primary : Color.white)
            .frame(maxWidth: 900, alignment: .leading)
            .padding(.horizontal, 80)
            .padding(.bottom, 32)
        }
    }
}

/// One library as a grid of posters, with its sort order, an Unwatched filter and a genre; more load as it scrolls.
struct LibraryGridView: View {
    @Environment(AppModel.self) private var model
    let library: MediaLibrary
    @State private var grid: LibraryGrid?
    @State private var watch: Watch?
    @State private var sort: LibrarySort = .title
    @State private var unwatched = false
    @State private var genre: String?

    private static let sorts: [(LibrarySort, String)] = [(.title, "Title"), (.added, "Added"), (.year, "Year"), (.rating, "Rating"), (.watched, "Watched")]
    /// A music library's artists, by name or by when they were added.
    private static let musicSorts: [(LibrarySort, String)] = [(.title, "Name"), (.added, "Added")]
    private let columns = Array(repeating: GridItem(.fixed(240), spacing: 48), count: 6)

    var body: some View {
        let shown = grid.flatMap { $0.libraryId == library.id ? $0 : nil }
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                HStack(spacing: 24) {
                    Picker("Sort", selection: $sort) {
                        ForEach(library.kind == .music ? Self.musicSorts : Self.sorts, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                    // Music has no watched state.
                    if library.kind != .music {
                        Toggle("Unwatched", isOn: $unwatched)
                    }
                    if let genres = shown?.genres, !genres.isEmpty {
                        Menu(genre ?? "All genres") {
                            Button("All genres") { genre = nil }
                            ForEach(genres, id: \.self) { option in
                                Button(option) { genre = option }
                            }
                        }
                    }
                }
                LazyVGrid(columns: columns, alignment: .leading, spacing: 48) {
                    ForEach(Array((shown?.items ?? []).enumerated()), id: \.element.id) { index, item in
                        NavigationLink(value: item) { LibraryCard(item: item, wide: false) }
                            .buttonStyle(.card)
                            .playsOnPlayPause(item)
                            .onAppear {
                                // About three rows short of what is loaded, the next page starts.
                                if let shown, shown.next != nil, index >= shown.items.count - 18 { model.core.loadMore() }
                            }
                    }
                }
                if let shown {
                    if let error = shown.error {
                        VStack(alignment: .leading, spacing: 16) {
                            Text(error).foregroundStyle(.secondary)
                            Button(strings.retry) { model.core.loadMore() }
                        }
                    } else if shown.loading {
                        ProgressView()
                    } else if shown.items.isEmpty {
                        Text(unwatched ? "Everything here is watched" : strings.libraryEmpty).foregroundStyle(.secondary)
                    }
                } else {
                    ProgressView()
                }
            }
        }
        .navigationTitle(library.name)
        .onAppear {
            watch = model.core.watchLibraryGrid { grid = $0 }
            open()
        }
        .onDisappear { watch?.cancel() }
        .onChange(of: sort) { open() }
        .onChange(of: unwatched) { open() }
        .onChange(of: genre) { open() }
    }

    private func open() {
        model.core.openGrid(libraryId: library.id, sort: sort, unwatched: unwatched, genre: genre)
    }
}

/// A horizontal row of cards that scrolls past the screen's edges, as the Home screen's rows do.
struct CardRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 40) { content }
                .padding(.vertical, 24)
        }
        .scrollClipDisabled()
    }
}

/// A poster (2:3), a square cover for music, or a wide (16:9) card with the title below, a bar for how far in, and a tick or the unwatched count.
struct LibraryCard: View {
    let item: MediaItem
    let wide: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Artwork(url: wide ? item.backdrop ?? item.poster : item.poster ?? item.backdrop, title: item.title)
                .frame(width: wide ? 400 : 240, height: wide ? 225 : item.kind.music ? 240 : 360)
                .overlay(alignment: .bottom) { ProgressStrip(progress: item.progress) }
                .overlay(alignment: .topTrailing) { WatchBadge(item: item) }
                .clipped()
            VStack(alignment: .leading, spacing: 2) {
                Text(item.cardTitle).font(.caption).lineLimit(1)
                if let subtitle = item.cardSubtitle {
                    Text(subtitle).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .frame(width: wide ? 400 : 240, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.bottom, 12)
        }
    }
}

/// A library as a wide tile with its name and how much it holds.
private struct LibraryTile: View {
    let library: MediaLibrary

    var body: some View {
        VStack(alignment: .leading) {
            Text(library.name).font(.title3).lineLimit(2)
            Spacer()
            Text("\(library.itemCount) items").font(.caption).foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(width: 400, height: 225, alignment: .leading)
        .background(library.kind == .shows ? Color.sofaLavenderDeep : library.kind == .music ? Color.sofaAmberDeep : Color.sofaPlumRaised)
    }
}

/// The item's image, or its title on a plain card while there is none.
struct Artwork: View {
    let url: String?
    let title: String

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                ZStack {
                    Rectangle().fill(.quaternary)
                    Text(title).font(.caption).multilineTextAlignment(.center).padding()
                }
            }
        }
    }
}

/// How far into an item the household is, along the bottom of its image.
private struct ProgressStrip: View {
    let progress: PlayProgress?

    var body: some View {
        if let progress, progress.inProgress, let fraction = progress.fraction?.doubleValue {
            ProgressView(value: fraction).padding(12)
        }
    }
}

/// A tick on what is watched; a show or season shows how many episodes are not.
private struct WatchBadge: View {
    let item: MediaItem

    var body: some View {
        if item.progress?.watched == true {
            Image(systemName: "checkmark.circle.fill").font(.title3).padding(10).accessibilityLabel(strings.libraryWatched)
        } else if item.unwatched > 0 && !item.kind.playable {
            Text("\(item.unwatched)").font(.caption).bold()
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(.tint, in: .capsule)
                .padding(10)
                .accessibilityLabel("\(item.unwatched) unwatched")
        }
    }
}

extension View {
    /// The remote's play/pause button plays [item] straight from its card, from where the household stopped; a song
    /// plays alone in Now playing.
    func playsOnPlayPause(_ item: MediaItem) -> some View {
        modifier(PlayOnPlayPause(item: item))
    }
}

private struct PlayOnPlayPause: ViewModifier {
    @Environment(AppModel.self) private var model
    let item: MediaItem

    func body(content: Content) -> some View {
        content.onPlayPauseCommand { model.playFromCard(item) }
    }
}

extension AppModel {
    /// Plays [item] from where the household stopped; a song plays alone in Now playing.
    func playFromCard(_ item: MediaItem) {
        if item.kind == .track {
            playMusic([item], from: item)
        } else if item.kind.playable {
            vod = VodRequest(item: item, fromStart: false)
        }
    }
}

extension MediaShelf {
    /// Continue watching and a row of episodes or videos are wide; anything else shows posters.
    var isWide: Bool {
        id == LibraryChoicesKt.CONTINUE_SHELF || (!items.isEmpty && items.allSatisfy { $0.kind == .episode || $0.kind == .video })
    }
}

extension MediaItem: @retroactive Identifiable {}

extension MediaLibrary: @retroactive Identifiable {}

extension MediaItem {
    /// An episode names its show on a card; anything else its own title.
    var cardTitle: String { kind == .episode ? showTitle ?? title : title }

    /// An episode's number and title, or a movie's year.
    var cardSubtitle: String? {
        if kind == .episode { return [episodeLabel, title].compactMap { $0 }.joined(separator: " · ") }
        if let extra { return extraLabel(extra) }
        return year.map { String($0.intValue) }
    }

    /// The running time as hours and minutes, or nil while it is unknown.
    var runtime: String? {
        guard let ms = durationMs?.int64Value, ms >= 30_000 else { return nil }
        return Duration.milliseconds(ms).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }
}

/// What kind of extra `kind` is, as a card's second line says it; by name, as `short` is not a name Swift sees.
func extraLabel(_ kind: ExtraKind) -> String {
    switch kind.name {
    case "TRAILER": strings.extraTrailer
    case "FEATURETTE": strings.extraFeaturette
    case "BEHIND_THE_SCENES": strings.extraBehindTheScenes
    case "DELETED_SCENE": strings.extraDeletedScene
    case "INTERVIEW": strings.extraInterview
    case "SCENE": strings.extraScene
    case "SHORT": strings.extraShort
    default: strings.extraOther
    }
}

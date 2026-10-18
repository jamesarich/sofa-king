import SwiftUI
import SofaKingCore

/// The Library tab: Continue watching, each library's recent additions, then the libraries themselves. Selecting a
/// card opens its page; the remote's play/pause button plays a movie or episode straight from its card.
struct LibraryHomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            content
                .navigationDestination(for: MediaItem.self) { ItemDetailView(itemId: $0.id) }
                .navigationDestination(for: MediaLibrary.self) { LibraryGridView(library: $0) }
        }
        .fullScreenCover(item: $model.vod) { request in
            VodPlayerView(request: request)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.libraryHome {
        case let ready as LibraryHomeStateReady:
            if ready.home.shelves.isEmpty && ready.home.libraries.isEmpty {
                ContentUnavailableView(
                    "Nothing here yet",
                    systemImage: "film.stack",
                    description: Text("The server adds what it finds on its next library scan.")
                )
            } else {
                shelves(ready.home)
            }
        case let failed as LibraryHomeStateFailed:
            VStack(spacing: 32) {
                Text("The library did not load").font(.title2)
                Text(failed.message).foregroundStyle(.secondary)
                Button("Try again") { model.core.refreshLibrary() }
            }
        case is LibraryHomeStateUnavailable:
            ContentUnavailableView(
                "This server has no libraries",
                systemImage: "film.stack",
                description: Text("Add a movies or shows folder in the server's portal, under Libraries.")
            )
        default:
            ProgressView("Loading the library")
        }
    }

    private func shelves(_ home: MediaHome) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 48) {
                ForEach(home.shelves, id: \.id) { shelf in
                    let wide = shelf.isWide
                    Section {
                        CardRow {
                            ForEach(shelf.items) { item in
                                NavigationLink(value: item) { LibraryCard(item: item, wide: wide) }
                                    .buttonStyle(.card)
                                    .playsOnPlayPause(item)
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
    }
}

/// One library as a grid of posters, with its sort order and an Unwatched filter; more load as it scrolls.
struct LibraryGridView: View {
    @Environment(AppModel.self) private var model
    let library: MediaLibrary
    @State private var grid: LibraryGrid?
    @State private var watch: Watch?
    @State private var sort: LibrarySort = .title
    @State private var unwatched = false

    private static let sorts: [(LibrarySort, String)] = [(.title, "Title"), (.added, "Recently added"), (.year, "Year"), (.rating, "Rating")]
    private let columns = Array(repeating: GridItem(.fixed(240), spacing: 48), count: 6)

    var body: some View {
        let shown = grid.flatMap { $0.libraryId == library.id ? $0 : nil }
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                HStack(spacing: 24) {
                    Picker("Sort", selection: $sort) {
                        ForEach(Self.sorts, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                    Toggle("Unwatched", isOn: $unwatched)
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
                            Button("Try again") { model.core.loadMore() }
                        }
                    } else if shown.loading {
                        ProgressView()
                    } else if shown.items.isEmpty {
                        Text(unwatched ? "Everything here is watched" : "Nothing here yet").foregroundStyle(.secondary)
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
    }

    private func open() {
        model.core.openGrid(libraryId: library.id, sort: sort, unwatched: unwatched)
    }
}

/// A horizontal row of cards that scrolls past the screen's edges, as the Home screen's rows do.
private struct CardRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 40) { content }
                .padding(.vertical, 24)
        }
        .scrollClipDisabled()
    }
}

/// A poster (2:3) or a wide (16:9) card with the title below, a bar for how far in, and a tick or the unwatched count.
struct LibraryCard: View {
    let item: MediaItem
    let wide: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Artwork(url: wide ? item.backdrop ?? item.poster : item.poster ?? item.backdrop, title: item.title)
                .frame(width: wide ? 400 : 240, height: wide ? 225 : 360)
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
        .background(library.kind == .shows ? Color.teal.opacity(0.35) : Color.indigo.opacity(0.35))
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
            Image(systemName: "checkmark.circle.fill").font(.title3).padding(10).accessibilityLabel("Watched")
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
    /// The remote's play/pause button plays [item] straight from its card, from where the household stopped.
    func playsOnPlayPause(_ item: MediaItem) -> some View {
        modifier(PlayOnPlayPause(item: item))
    }
}

private struct PlayOnPlayPause: ViewModifier {
    @Environment(AppModel.self) private var model
    let item: MediaItem

    func body(content: Content) -> some View {
        content.onPlayPauseCommand {
            if item.kind.playable { model.vod = VodRequest(item: item, fromStart: false) }
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
        return year.map { String($0.intValue) }
    }

    /// The running time as hours and minutes, or nil while it is unknown.
    var runtime: String? {
        guard let ms = durationMs?.int64Value, ms >= 30_000 else { return nil }
        return Duration.milliseconds(ms).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }
}

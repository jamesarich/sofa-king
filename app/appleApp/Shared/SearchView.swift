import SwiftUI
import SofaKingCore

/// The page the Library tab's Search button pushes.
struct LibrarySearchRoute: Hashable {}

extension View {
    /// A Search button above the Library tab's page, which pushes the search page; only while the library is showing.
    func librarySearchButton(shown: Bool) -> some View {
        safeAreaInset(edge: .top, alignment: .leading) {
            if shown { LibrarySearchLink() }
        }
        .navigationDestination(for: LibrarySearchRoute.self) { _ in SearchView(opensItemsHere: true) }
    }
}

private struct LibrarySearchLink: View {
    @PageLayout private var metrics

    var body: some View {
        NavigationLink(value: LibrarySearchRoute()) {
            Label(searchStrings.search, systemImage: "magnifyingglass")
        }
        .padding(.horizontal, metrics.touch ? metrics.margin : 48)
    }
}

/// One search across live TV and the libraries, with the system's search keyboard, as the other apps' Search page
/// answers it: On now (the channels whose number or name matches, then those whose program on now does), a shelf per
/// library kind (by title, show or cast), then On later (the programs coming up that match, soonest first). Selecting
/// a channel on now plays it full screen and Back returns here; a program coming up opens its details. A library card
/// opens the item's page: here when the Library tab pushed the page (`opensItemsHere`), and on the Library tab when the
/// Live tab did, since the library's player belongs to that tab. The libraries are asked once a query has rested for
/// the debounce the shared search names, so typing a word asks the server once; live TV answers as you type.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    var opensItemsHere: Bool
    @State private var query = ""
    @State private var found: SearchSections?
    @State private var searching = false
    @State private var playing: Channel?
    @State private var opened: LiveHit?
    @PageLayout private var metrics

    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The channels the Live tab shows, kids mode's only while it is on; nil before the lineup is in.
    private var lineup: Lineup? { (model.state as? LineupStateReady)?.visible }

    var body: some View {
        let live = lineup.map { model.core.searchLive(lineup: $0, query: trimmed) }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: metrics.sectionSpacing) {
                if let live, !live.now.isEmpty {
                    liveRow(searchStrings.onNow, hits: live.now, later: false)
                }
                if let found, found.found {
                    ForEach(found.rows, id: \.group) { row in
                        libraryRow(row, in: found)
                    }
                }
                if let live, !live.later.isEmpty {
                    liveRow(searchStrings.onLater, hits: live.later, later: true)
                }
                if nothingFound(live) {
                    Text(searchStrings.none).font(.title3).foregroundStyle(.secondary).pageMargin(metrics)
                }
                if searching { ProgressView() }
            }
        }
        .navigationTitle(searchStrings.search)
        .searchable(text: $query, prompt: lineup == nil ? searchStrings.hint : searchStrings.liveHint)
        .task(id: trimmed) { await search(trimmed) }
        .navigationDestination(item: $opened) { hit in
            if let lineup {
                ProgramDetailView(lineup: lineup, channel: hit.channel, airing: hit.program) { playing = hit.channel }
            }
        }
        .fullScreenCover(item: $playing) { channel in
            PlayerView(channel: channel)
        }
    }

    /// Whether a query has its whole answer and none of it matched: live TV found nothing, and the libraries
    /// answered with nothing or are not searched on this server.
    private func nothingFound(_ live: LiveSearchResults?) -> Bool {
        guard !trimmed.isEmpty, !searching, live?.found != true else { return false }
        return model.libraryShown ? found?.found == false : true
    }

    private func liveRow(_ title: String, hits: [LiveHit], later: Bool) -> some View {
        Section {
            CardRow {
                ForEach(hits, id: \.key) { hit in
                    Button {
                        if later { opened = hit } else { playing = hit.channel }
                    } label: {
                        LiveHitCard(hit: hit, time: later ? hit.program.map { startsAt($0) } : nil)
                    }
                    .buttonStyle(.card)
                    .contextMenu {
                        Button(strings.libraryDetails, systemImage: "info.circle") { opened = hit }
                    }
                    .accessibilityIdentifier("search-hit-\(hit.channel.name)")
                }
            }
        } header: {
            Text(title).font(.title3).pageMargin(metrics)
        }
    }

    private func libraryRow(_ row: SearchSection, in found: SearchSections) -> some View {
        let wide = row.group == .episodes || row.group == .videos
        return Section {
            CardRow {
                ForEach(row.items) { item in
                    if opensItemsHere {
                        NavigationLink(value: item) { LibraryCard(item: item, wide: wide) }
                            .buttonStyle(.card)
                            .playsOnPlayPause(item)
                    } else {
                        Button { model.requestedDetails = item.id } label: { LibraryCard(item: item, wide: wide) }
                            .buttonStyle(.card)
                    }
                }
            }
        } header: {
            Text(searchStrings.groupTitle(group: row.group, sections: found)).font(.title3).pageMargin(metrics)
        }
    }

    /// The day and time a program coming up starts.
    private func startsAt(_ program: Program) -> String {
        model.core.start(of: program).formatted(.dateTime.weekday().hour().minute())
    }

    /// Asks the libraries for [text] once it has rested, dropping the answer if a newer query came in meanwhile.
    private func search(_ text: String) async {
        guard !text.isEmpty, model.libraryShown else {
            found = nil
            searching = false
            return
        }
        do {
            try await Task.sleep(for: .milliseconds(model.core.searchDebounceMs))
            searching = true
            let sections = try await model.core.searchLibrary(query: text)
            if Task.isCancelled { return }
            found = sections
            searching = false
        } catch is CancellationError {
            return
        } catch {
            found = nil
            searching = false
        }
    }
}

/// A channel a search found, as a wide card: its number and name, the program it was found by, and for one coming up,
/// when it starts.
private struct LiveHitCard: View {
    let hit: LiveHit
    let time: String?
    @PageLayout private var metrics

    var body: some View {
        VStack(alignment: .leading, spacing: metrics.touch ? 4 : 8) {
            Text("\(hit.channel.number.formatted()) \(hit.channel.name)")
                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text(hit.program?.title ?? hit.channel.name).font(.headline).lineLimit(2)
            if let subTitle = hit.program?.subTitle {
                Text(subTitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if let time { Text(time).font(.caption).lineLimit(1) }
        }
        .padding(metrics.touch ? 12 : 24)
        .frame(width: metrics.wide, height: metrics.wide * 9 / 16, alignment: .topLeading)
        .background(Color.sofaPlumRaised)
    }
}

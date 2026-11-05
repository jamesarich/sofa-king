import SwiftUI
import SofaKingCore

/// The page the Library tab's Search button pushes.
struct LibrarySearchRoute: Hashable {}

extension View {
    /// A Search button above the Library tab's page, which pushes the search page; only while the library is showing.
    func librarySearchButton(shown: Bool) -> some View {
        safeAreaInset(edge: .top, alignment: .leading) {
            if shown {
                NavigationLink(value: LibrarySearchRoute()) {
                    Label(searchStrings.search, systemImage: "magnifyingglass")
                }
                .padding(.horizontal, 48)
            }
        }
        .navigationDestination(for: LibrarySearchRoute.self) { _ in LibrarySearchView() }
    }
}

/// Search across every library by title, show or cast, with the system's search keyboard: a shelf per kind, as the
/// other apps group them, and a card opens the same page as anywhere else. A query rests for the debounce the shared
/// search names before it asks the server, so typing a word asks once.
struct LibrarySearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var found: SearchSections?
    @State private var searching = false

    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 48) {
                if let found {
                    if found.found {
                        ForEach(found.rows, id: \.group) { row in
                            let wide = row.group == .episodes || row.group == .videos
                            Section {
                                CardRow {
                                    ForEach(row.items) { item in
                                        NavigationLink(value: item) { LibraryCard(item: item, wide: wide) }
                                            .buttonStyle(.card)
                                            .playsOnPlayPause(item)
                                    }
                                }
                            } header: {
                                Text(searchStrings.groupTitle(group: row.group, sections: found)).font(.title3)
                            }
                        }
                    } else if !searching {
                        Text(searchStrings.none).font(.title3).foregroundStyle(.secondary)
                    }
                }
                if searching { ProgressView() }
            }
        }
        .navigationTitle(searchStrings.search)
        .searchable(text: $query, prompt: searchStrings.hint)
        .task(id: trimmed) { await search(trimmed) }
    }

    /// Asks for [text] once it has rested, dropping the answer if a newer query came in meanwhile.
    private func search(_ text: String) async {
        guard !text.isEmpty else {
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

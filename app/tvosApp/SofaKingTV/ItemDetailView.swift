import SwiftUI
import SofaKingCore

/// An item's page: its backdrop, details and buttons to play, resume, start over and mark watched. A show adds its
/// seasons, opening on the one with the next episode, and that season's episodes, which play when selected; holding
/// the touch surface on one offers Details, its own page, Play from beginning and Mark watched. Under them are the
/// item's Extras, which play when selected, and More like this, which opens the other item's page.
struct ItemDetailView: View {
    @Environment(AppModel.self) private var model
    let itemId: String
    @State private var state: ItemDetailState = ItemDetailStateLoading.shared
    @State private var watch: Watch?
    @State private var pickedSeason: String?
    @State private var season: ItemDetailState?
    @State private var seasonWatch: Watch?
    @State private var opened: MediaItem?

    var body: some View {
        Group {
            switch state {
            case let ready as ItemDetailStateReady:
                page(ready.detail)
            case let failed as ItemDetailStateFailed:
                VStack(spacing: 32) {
                    Text("The library didn't load").font(.title2)
                    Text(failed.message).foregroundStyle(.secondary)
                    Button("Try again") { model.core.refreshItem(id: itemId) }
                }
            default:
                ProgressView()
            }
        }
        .onAppear {
            watch = model.core.watchItem(id: itemId) { state = $0 }
        }
        .onDisappear {
            watch?.cancel()
            seasonWatch?.cancel()
        }
        .onChange(of: seasonId, initial: true) { _, id in
            seasonWatch?.cancel()
            season = nil
            guard let id else { return }
            seasonWatch = model.core.watchItem(id: id) { season = $0 }
        }
    }

    /// The season shown under a show: the viewer's pick, else the one with the next episode.
    private var seasonId: String? {
        pickedSeason ?? (state as? ItemDetailStateReady).flatMap { LibraryChoicesKt.initialSeasonId(detail: $0.detail) }
    }

    private func page(_ detail: MediaItemDetail) -> some View {
        let item = detail.item
        return ScrollView {
            VStack(alignment: .leading, spacing: 48) {
                HStack(alignment: .top, spacing: 60) {
                    Artwork(url: item.poster, title: item.title)
                        .frame(width: 300, height: 450)
                        .clipShape(.rect(cornerRadius: 16))
                    summary(detail)
                }
                let seasons = detail.children.filter { $0.kind == .season }
                if !seasons.isEmpty {
                    seasonPicker(seasons)
                    if let ready = season as? ItemDetailStateReady {
                        episodes(ready.detail.children)
                    } else if season != nil {
                        ProgressView()
                    }
                } else if item.kind == .season {
                    episodes(detail.children)
                }
                if !detail.extras.isEmpty {
                    Section {
                        CardRow {
                            ForEach(detail.extras) { extra in
                                Button { model.vod = VodRequest(item: extra, fromStart: false) } label: { LibraryCard(item: extra, wide: true) }
                                    .buttonStyle(.card)
                            }
                        }
                    } header: {
                        Text("Extras").font(.title3)
                    }
                }
                if !detail.related.isEmpty {
                    Section {
                        CardRow {
                            ForEach(detail.related) { other in
                                NavigationLink(value: other) { LibraryCard(item: other, wide: other.kind == .video) }
                                    .buttonStyle(.card)
                                    .playsOnPlayPause(other)
                            }
                        }
                    } header: {
                        Text("More like this").font(.title3)
                    }
                }
            }
            .padding(.top, 40)
        }
        .background {
            if let backdrop = item.backdrop {
                Artwork(url: backdrop, title: "")
                    .overlay(LinearGradient(colors: [.black.opacity(0.5), .black.opacity(0.95)], startPoint: .top, endPoint: .bottom))
                    .ignoresSafeArea()
            }
        }
        .navigationDestination(item: $opened) { ItemDetailView(itemId: $0.id) }
    }

    /// With Settings › Library › Hide spoilers on, an episode not started yet keeps its overview back and blurs its still.
    private func hidesSpoilers(_ item: MediaItem) -> Bool {
        model.settings?.hideSpoilers == true && LibraryChoicesKt.hidesSpoilers(item: item)
    }

    private func summary(_ detail: MediaItemDetail) -> some View {
        let item = detail.item
        let action = LibraryChoicesKt.primaryAction(detail: detail)
        return VStack(alignment: .leading, spacing: 20) {
            if item.kind == .episode, let show = item.showTitle {
                Text(show).font(.headline).foregroundStyle(.secondary)
            }
            Text(item.kind == .episode ? [item.episodeLabel, item.title].compactMap { $0 }.joined(separator: " · ") : item.title)
                .font(.title).bold()
            Text(metaLine(item)).foregroundStyle(.secondary)
            if let tagline = detail.tagline, !tagline.isEmpty {
                Text(tagline).italic()
            }
            if let overview = item.overview, !overview.isEmpty {
                if hidesSpoilers(item) {
                    Text(spoilerHidden).italic().foregroundStyle(.secondary)
                } else {
                    Text(overview).lineLimit(6)
                }
            }
            HStack(spacing: 24) {
                if let action {
                    Button(primaryLabel(action, playsItself: item.kind.playable), systemImage: "play.fill") {
                        model.vod = VodRequest(item: action.item, fromStart: false)
                    }
                    if action is PrimaryActionResume {
                        Button("Play from beginning", systemImage: "arrow.counterclockwise") {
                            model.vod = VodRequest(item: action.item, fromStart: true)
                        }
                    }
                }
                let watched = item.progress?.watched == true || (!item.kind.playable && item.children > 0 && item.unwatched == 0)
                Button(watched ? "Mark unwatched" : "Mark watched", systemImage: watched ? "eye.slash" : "checkmark") {
                    model.core.setWatched(item: item, watched: !watched)
                }
            }
            if !detail.directors.isEmpty {
                Text("Directed by \(detail.directors.joined(separator: ", "))").font(.caption).foregroundStyle(.secondary)
            }
            if !detail.cast.isEmpty {
                Text("Cast: \(detail.cast.prefix(6).joined(separator: ", "))").font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
        }
    }

    private func seasonPicker(_ seasons: [MediaItem]) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 20) {
                ForEach(seasons) { s in
                    let label = s.season.map { $0.intValue == 0 ? "Specials" : "Season \($0.intValue)" } ?? s.title
                    if s.id == seasonId {
                        Button(label) { pickedSeason = s.id }.buttonStyle(.borderedProminent)
                    } else {
                        Button(label) { pickedSeason = s.id }.buttonStyle(.bordered)
                    }
                }
            }
            .padding(.vertical, 16)
        }
        .scrollClipDisabled()
    }

    private func episodes(_ episodes: [MediaItem]) -> some View {
        LazyVStack(alignment: .leading, spacing: 24) {
            ForEach(episodes) { episode in
                Button { model.vod = VodRequest(item: episode, fromStart: false) } label: { EpisodeRow(episode: episode, spoilersHidden: hidesSpoilers(episode)) }
                    .buttonStyle(.card)
                    .contextMenu {
                        Button("Details", systemImage: "info.circle") { opened = episode }
                        if episode.progress?.inProgress == true {
                            Button("Play from beginning", systemImage: "arrow.counterclockwise") {
                                model.vod = VodRequest(item: episode, fromStart: true)
                            }
                        }
                        let watched = episode.progress?.watched == true
                        Button(watched ? "Mark unwatched" : "Mark watched", systemImage: watched ? "eye.slash" : "checkmark") {
                            model.core.setWatched(item: episode, watched: !watched)
                        }
                    }
            }
        }
    }

    /// Year, running time or seasons, rating, content rating and picture.
    private func metaLine(_ item: MediaItem) -> String {
        var parts: [String] = []
        if let year = item.year { parts.append(String(year.intValue)) }
        if item.kind == .show, item.children > 0 {
            parts.append(item.children == 1 ? "1 season" : "\(item.children) seasons")
        } else if let runtime = item.runtime {
            parts.append(runtime)
        }
        if let rating = item.rating { parts.append("★ \(rating.doubleValue.formatted(.number.precision(.fractionLength(1))))") }
        if let content = item.contentRating { parts.append(content) }
        if let height = item.videoHeight?.intValue { if height >= 2000 { parts.append("4K") } else if height >= 720 { parts.append("HD") } }
        if item.hdr { parts.append("HDR") }
        if !item.genres.isEmpty { parts.append(item.genres.prefix(3).joined(separator: ", ")) }
        return parts.joined(separator: " · ")
    }

    /// "Resume from 30:00" or "Play" for the item itself; a show names the episode, as in "Resume S2 E2".
    private func primaryLabel(_ action: PrimaryAction, playsItself: Bool) -> String {
        let episode = playsItself ? nil : action.item.episodeLabel
        if let resume = action as? PrimaryActionResume {
            return episode.map { "Resume \($0)" } ?? "Resume from \(playTime(resume.positionMs))"
        }
        return episode.map { "Play \($0)" } ?? "Play"
    }
}

/// Stands in for an unwatched episode's overview while Hide spoilers is on.
private let spoilerHidden = "Unwatched episode. Hide spoilers keeps its summary back."

/// An episode's still, number, title, running time, air date, overview and progress; with `spoilersHidden` the still
/// is blurred and the overview kept back.
private struct EpisodeRow: View {
    let episode: MediaItem
    let spoilersHidden: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 32) {
            Artwork(url: episode.backdrop ?? episode.poster, title: episode.title)
                .frame(width: 320, height: 180)
                .blur(radius: spoilersHidden ? 24 : 0)
                .overlay(alignment: .bottom) {
                    if let progress = episode.progress, progress.inProgress, let fraction = progress.fraction?.doubleValue {
                        ProgressView(value: fraction).padding(10)
                    }
                }
                .clipped()
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text([episode.episodeLabel, episode.title].compactMap { $0 }.joined(separator: " · ")).font(.headline).lineLimit(1)
                    if episode.progress?.watched == true {
                        Image(systemName: "checkmark.circle.fill").accessibilityLabel("Watched")
                    }
                }
                let meta = [episode.runtime, episode.aired].compactMap { $0 }
                if !meta.isEmpty {
                    Text(meta.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                }
                if let overview = episode.overview {
                    if spoilersHidden {
                        Text(spoilerHidden).font(.caption).italic().foregroundStyle(.secondary)
                    } else {
                        Text(overview).font(.caption).lineLimit(3)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(width: 1500, alignment: .leading)
    }
}

/// A position as `m:ss`, or `h:mm:ss` from an hour on.
func playTime(_ ms: Int64) -> String {
    let seconds = Int(ms / 1000)
    let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60)
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// A library item to play, from the start or from where the household stopped in it.
struct VodRequest: Identifiable, Hashable {
    let item: MediaItem
    let fromStart: Bool
    var id: String { item.id }
}

/// A library item full screen on AVPlayer, through a server session core opens, renews and releases. The system
/// player's transport bar scrubs; a converted stream scrubs within what the server has converted so far. The
/// position is saved as it plays and when it stops; leaving releases the session, and backgrounding releases it
/// until the app returns, when the item reopens where it was. Reaching the end of an episode offers the next one,
/// played after a countdown; reaching the end of anything else returns to the page.
struct VodPlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    let request: VodRequest
    @State private var player = AVPlayer()
    @State private var stream: PlaybackStream?
    @State private var failure: String?
    @State private var replaced: Watch?
    @State private var itemObservers: [AnyCancellable] = []
    @State private var positionMs: Int64 = 0
    @State private var durationMs: Int64 = 0
    @State private var ended = false
    @State private var backgrounded = false
    @State private var next: MediaItem?

    var body: some View {
        ZStack {
            VodController(player: player).ignoresSafeArea()
            if let failure {
                VStack(spacing: 24) {
                    Text(failure)
                    Button("Try again") { Task { await open() } }
                }
                .padding()
                .background(.thinMaterial, in: .rect(cornerRadius: 16))
            }
            if let next {
                UpNextCard(next: next, onPlay: { play(next) }, onCancel: { dismiss() })
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(60)
            }
        }
        .task { await open() }
        .onAppear {
            // A renewal that finds the stream replaced hands back the new URL for the same item.
            replaced = model.core.watchReplaced { s in
                if s.itemId == request.item.id { load(s) }
            }
        }
        .onDisappear {
            replaced?.cancel()
            itemObservers.removeAll()
            if !ended && stream != nil { stopped() }
            player.replaceCurrentItem(with: nil)
            model.core.stopWatching()
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                tick()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background where stream != nil && !ended:
                backgrounded = true
                stopped()
                player.replaceCurrentItem(with: nil)
                Task { try? await model.core.release() }
            case .active where backgrounded:
                backgrounded = false
                Task {
                    do {
                        if let s = try await model.core.reopen() { load(s) } else { await open() }
                    } catch {
                        failure = error.localizedDescription
                    }
                }
            default:
                break
            }
        }
    }

    private func open() async {
        failure = nil
        do {
            load(try await model.core.playItem(item: request.item, fromStart: request.fromStart))
        } catch {
            failure = error.localizedDescription
        }
    }

    /// Plays `s`, titled for the transport bar; once it is ready a stream that starts before the asked-for point seeks there.
    private func load(_ s: PlaybackStream) {
        guard let url = URL(string: s.url) else { return }
        stream = s
        positionMs = s.startAtMs
        durationMs = s.itemDurationMs?.int64Value ?? 0
        let item = AVPlayerItem(url: url)
        item.externalMetadata = metadata()
        let seekMs = s.startAtMs - s.offsetMs
        var sought = seekMs <= 0
        itemObservers = [
            item.publisher(for: \.status).filter { $0 == .readyToPlay }.sink { _ in
                failure = nil
                guard !sought else { return }
                sought = true
                player.seek(to: CMTime(value: seekMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
            },
            item.publisher(for: \.status).filter { $0 == .failed }.sink { _ in
                failure = item.error?.localizedDescription ?? "The item stopped playing"
            },
            NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item).sink { _ in finished() },
        ]
        player.replaceCurrentItem(with: item)
        player.play()
    }

    /// The position in the item, saved on the server as it plays; core decides how often it writes.
    private func tick() {
        guard let s = stream, let item = player.currentItem, item.status == .readyToPlay else { return }
        let seconds = player.currentTime().seconds
        guard seconds.isFinite else { return }
        positionMs = Int64(seconds * 1000) + s.offsetMs
        if let known = s.itemDurationMs?.int64Value {
            durationMs = known
        } else if item.duration.seconds.isFinite {
            durationMs = Int64(item.duration.seconds * 1000) + s.offsetMs
        }
        if player.rate > 0 { model.core.itemProgress(itemId: request.item.id, positionMs: positionMs, durationMs: durationMs) }
    }

    private func stopped() {
        model.core.itemStopped(itemId: request.item.id, positionMs: positionMs, durationMs: durationMs)
    }

    private func finished() {
        guard !ended else { return }
        ended = true
        let end = durationMs > 0 ? durationMs : positionMs
        model.core.itemStopped(itemId: request.item.id, positionMs: end, durationMs: durationMs)
        Task {
            if let upNext = try? await model.core.upNext(item: request.item) {
                next = upNext
            } else {
                dismiss()
            }
        }
    }

    /// Swaps the cover to [item], which opens on its own session as this one releases.
    private func play(_ item: MediaItem) {
        model.vod = VodRequest(item: item, fromStart: false)
    }

    /// The title, and for an episode its show and number, as the transport bar's info panel shows them.
    private func metadata() -> [AVMetadataItem] {
        let item = request.item
        var entries: [(AVMetadataIdentifier, String)] = [(.commonIdentifierTitle, item.title)]
        if item.kind == .episode {
            let subtitle = [item.showTitle, item.episodeLabel].compactMap { $0 }.joined(separator: " · ")
            if !subtitle.isEmpty { entries.append((.iTunesMetadataTrackSubTitle, subtitle)) }
        }
        if let overview = item.overview { entries.append((.commonIdentifierDescription, overview)) }
        return entries.map { identifier, value in
            let entry = AVMutableMetadataItem()
            entry.identifier = identifier
            entry.value = value as NSString
            entry.extendedLanguageTag = "und"
            return entry.copy() as! AVMetadataItem
        }
    }
}

/// The Up next card once an episode ends: the next one's still, number and title, counting down to playing it.
/// Play now or the countdown plays it; Cancel or Menu returns to the page.
private struct UpNextCard: View {
    let next: MediaItem
    let onPlay: () -> Void
    let onCancel: () -> Void
    @State private var secondsLeft = 10
    @FocusState private var playFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Up next").font(.headline).foregroundStyle(.tint)
            Artwork(url: next.backdrop ?? next.poster, title: next.title)
                .frame(width: 480, height: 270)
                .clipShape(.rect(cornerRadius: 12))
            if let subtitle = [next.showTitle, next.episodeLabel].compactMap({ $0 }).joined(separator: " · ").nilIfEmpty {
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Text(next.title).font(.title3).lineLimit(2)
            ProgressView(value: Double(10 - secondsLeft), total: 10)
            HStack(spacing: 24) {
                Button("Play now (\(secondsLeft))", systemImage: "play.fill", action: onPlay).focused($playFocused)
                Button("Cancel", action: onCancel)
            }
        }
        .frame(width: 480)
        .padding(32)
        .background(.thinMaterial, in: .rect(cornerRadius: 24))
        .onAppear { playFocused = true }
        .onExitCommand(perform: onCancel)
        .task {
            while secondsLeft > 0 {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                secondsLeft -= 1
            }
            onPlay()
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// The system player UI, which handles the Siri Remote's play, pause, scrubbing and Menu.
private struct VodController: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}
}

import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// A library item or a recording to play, from the start or from where the household stopped in it.
struct VodRequest: Identifiable, Hashable {
    enum Source: Hashable {
        case item(MediaItem)
        case recording(DvrRecording)
    }

    let source: Source
    let fromStart: Bool

    init(item: MediaItem, fromStart: Bool) {
        source = .item(item)
        self.fromStart = fromStart
    }

    init(recording: DvrRecording, fromStart: Bool) {
        source = .recording(recording)
        self.fromStart = fromStart
    }

    /// The library item's or the recording's id, which the session's stream carries as its `itemId`.
    var id: String {
        switch source {
        case .item(let item): item.id
        case .recording(let recording): recording.id
        }
    }
}

/// A library item or recording full screen on AVPlayer, through a server session core opens, renews and releases.
/// The system player's transport bar scrubs; a converted stream scrubs within what the server has converted so far.
/// A recording's commercial breaks show on the bar, and with Skip commercials on, playback that runs into one jumps
/// to its end. The position is saved as it plays and when it stops; leaving releases the session, and backgrounding
/// releases it until the app returns, when the item reopens where it was. Reaching the end returns to the page.
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
    /// Where the last tick found the player in the item, so a break is skipped only when playback runs into it.
    @State private var lastPositionMs: Int64 = 0

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
        }
        .task { await open() }
        .onAppear {
            // A renewal that finds the stream replaced hands back the new URL for the same item.
            replaced = model.core.watchReplaced { s in
                if s.itemId == request.id { load(s) }
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
            switch request.source {
            case .item(let item):
                load(try await model.core.playItem(item: item, fromStart: request.fromStart))
            case .recording(let recording):
                load(try await model.core.playRecording(recording: recording, fromStart: request.fromStart))
            }
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
        item.interstitialTimeRanges = breaks.map { b in
            let start = CMTime(value: b.startMs - s.offsetMs, timescale: 1000)
            return AVInterstitialTimeRange(timeRange: CMTimeRange(start: start, duration: CMTime(value: b.endMs - b.startMs, timescale: 1000)))
        }
        lastPositionMs = s.startAtMs
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
        skipBreak(from: lastPositionMs, to: positionMs, in: s)
        lastPositionMs = positionMs
        guard player.rate > 0 else { return }
        switch request.source {
        case .item(let item): model.core.itemProgress(itemId: item.id, positionMs: positionMs, durationMs: durationMs)
        case .recording(let recording): model.core.recordingProgress(recordingId: recording.id, positionMs: positionMs, durationMs: durationMs)
        }
    }

    /// The recording's commercial breaks; a library item has none.
    private var breaks: [CommercialBreak] {
        if case .recording(let recording) = request.source { return recording.breaks }
        return []
    }

    /// With Skip commercials on, jumps to the end of a break that playback ran into between two ticks; a seek into
    /// a break stays there. A break end past what the server has converted restarts the conversion there.
    private func skipBreak(from previousMs: Int64, to nowMs: Int64, in s: PlaybackStream) {
        guard model.settings?.skipCommercials == true, player.rate > 0,
              let b = breaks.first(where: { previousMs < $0.startMs && nowMs >= $0.startMs && nowMs < $0.endMs })
        else { return }
        let target = CMTime(value: b.endMs - s.offsetMs, timescale: 1000)
        let seekableEnd = player.currentItem?.seekableTimeRanges.last?.timeRangeValue.end ?? .zero
        if s.mode == "direct" || CMTimeCompare(target, seekableEnd) <= 0 {
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        } else {
            Task {
                if let restarted = try? await model.core.restartItemAt(positionMs: b.endMs) { load(restarted) }
            }
        }
    }

    private func stopped() {
        report(stoppedAt: positionMs)
    }

    private func report(stoppedAt ms: Int64) {
        switch request.source {
        case .item(let item): model.core.itemStopped(itemId: item.id, positionMs: ms, durationMs: durationMs)
        case .recording(let recording): model.core.recordingStopped(recordingId: recording.id, positionMs: ms, durationMs: durationMs)
        }
    }

    private func finished() {
        guard !ended else { return }
        ended = true
        let end = durationMs > 0 ? durationMs : positionMs
        report(stoppedAt: end)
        dismiss()
    }

    /// The title, and for an episode its show and number or for a recording when it aired, as the transport bar's
    /// info panel shows them.
    private func metadata() -> [AVMetadataItem] {
        var entries: [(AVMetadataIdentifier, String)] = []
        switch request.source {
        case .item(let item):
            entries.append((.commonIdentifierTitle, item.title))
            if item.kind == .episode {
                let subtitle = [item.showTitle, item.episodeLabel].compactMap { $0 }.joined(separator: " · ")
                if !subtitle.isEmpty { entries.append((.iTunesMetadataTrackSubTitle, subtitle)) }
            }
            if let overview = item.overview { entries.append((.commonIdentifierDescription, overview)) }
        case .recording(let recording):
            entries.append((.commonIdentifierTitle, recording.title))
            entries.append((.iTunesMetadataTrackSubTitle, model.core.recordingSubtitle(recording)))
            if let summary = model.core.recordingSummary(recording: recording) { entries.append((.commonIdentifierDescription, summary)) }
        }
        return entries.map { identifier, value in
            let entry = AVMutableMetadataItem()
            entry.identifier = identifier
            entry.value = value as NSString
            entry.extendedLanguageTag = "und"
            return entry.copy() as! AVMetadataItem
        }
    }
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

import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// The guide's preview, as on the other apps: the focused channel plays muted after 350 ms, its sound comes on after
/// 5 seconds of rest (unless Settings turns preview sound off), and after the rest Settings sets (10 seconds unless
/// changed, or never) it opens full screen through `openFullScreen`, continuing the preview's stream and session. Each move
/// of focus starts the rest again, muted. The channel just watched does not open by itself again until focus moves to another,
/// nor does any while VoiceOver runs or the preview failed. It plays on the app's one live `AVPlayer`, which the full
/// screen player takes over.
@MainActor
@Observable
final class GuidePreview {
    /// The channel focus rests on, or nil while focus is off the grid.
    private(set) var channelId: String?
    /// The program focus rests on, for the band's text.
    private(set) var program: Program?
    /// The channel the preview stream belongs to.
    private(set) var playingId: String?
    /// True when the last open for the focused channel failed; the band says Preview unavailable.
    private(set) var failed = false
    /// The channel the player was just left on, which does not open by itself until focus moves.
    var suppressed: String?
    @ObservationIgnored private weak var model: AppModel?
    @ObservationIgnored var openFullScreen: (String) -> Void = { _ in }
    @ObservationIgnored private var tuning: Task<Void, Never>?
    @ObservationIgnored private var resting: Task<Void, Never>?
    /// The preview item's captions, which follow the CC setting as the full screen's do.
    @ObservationIgnored private var captions: [AnyCancellable] = []
    private var active = false

    /// The guide is the page on top and the app is in front: the preview may tune.
    func activate(_ model: AppModel) {
        self.model = model
        guard !active else { return }
        active = true
        if let channelId { focus(channelId, program: program) }
    }

    /// The guide is no longer on top. On `handoff` the full screen takes the stream and session over as they are;
    /// otherwise the preview lets them go.
    func deactivate(handoff: Bool) {
        active = false
        tuning?.cancel()
        resting?.cancel()
        guard !handoff, let model else { return }
        model.livePlayer.replaceCurrentItem(with: nil)
        playingId = nil
        model.core.stopWatching()
    }

    /// Focus came to `program` on `channelId`, or left the grid (nil). Another channel lifts the suppression.
    func focus(_ channelId: String?, program: Program?) {
        if channelId != nil && channelId != suppressed { suppressed = nil }
        let moved = channelId != self.channelId
        self.channelId = channelId
        self.program = program
        if moved { failed = false }
        restart()
        guard active, let channelId, channelId != playingId || model?.livePlayer.currentItem == nil else { return }
        tuning?.cancel()
        tuning = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(PreviewDwellKt.PREVIEW_DEBOUNCE_MS))
            guard !Task.isCancelled else { return }
            await self?.tune(channelId)
        }
    }

    private func tune(_ channelId: String) async {
        guard let model else { return }
        do {
            let stream = try await model.core.previewChannel(channelId: channelId)
            guard active, self.channelId == channelId, let url = URL(string: stream.url) else { return }
            await StreamStart.settled(url, core: model.core)
            guard active, self.channelId == channelId else { return }
            let player = model.livePlayer
            if (player.currentItem?.asset as? AVURLAsset)?.url != url {
                let item = AVPlayerItem(url: url)
                captions = StreamCaptions.follow(item, on: player, model: model)
                player.replaceCurrentItem(with: item)
            }
            player.play()
            playingId = channelId
            failed = false
        } catch {
            if self.channelId == channelId { failed = true }
        }
    }

    private func restart() {
        model?.livePlayer.isMuted = true
        resting?.cancel()
        guard active, let channelId, let model else { return }
        let sound = model.settings?.previewSound ?? true
        // The rest timers are core's `PreviewDwell`, as on the other apps; this only acts on what it says.
        let dwell = PreviewDwell(
            suppressedChannelId: suppressed,
            fullScreenAfterMs: model.settings?.autoOpenDelay.afterMs,
            pointerDriven: false
        )
        dwell.onFocus(channelId: channelId, nowMs: uptimeMs())
        resting = Task { [weak self] in
            while let wait = dwell.nextTransitionMs(nowMs: uptimeMs())?.int64Value {
                try? await Task.sleep(for: .milliseconds(wait))
                guard !Task.isCancelled, let self else { return }
                let now = uptimeMs()
                // Music playing keeps the preview silent; the full screen ends the music.
                if sound && !dwell.isMuted(nowMs: now) && self.playingId == channelId && (model.music.queue == nil || model.music.paused) {
                    model.livePlayer.isMuted = false
                }
                guard dwell.opensFullScreen(nowMs: now) else { continue }
                guard self.active, self.channelId == channelId, self.playingId == channelId,
                      !self.failed, self.suppressed != channelId, !UIAccessibility.isVoiceOverRunning else { return }
                self.openFullScreen(channelId)
                return
            }
        }
    }
}

/// A monotonic clock in milliseconds, the one `PreviewDwell` reads.
private func uptimeMs() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
}

/// The band over the guide: the preview's picture beside the focused program's channel, title, times and summary.
struct PreviewBand: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    let preview: GuidePreview

    var body: some View {
        let channel = preview.channelId.flatMap { id in lineup.channels.first { $0.guideId == id } }
        HStack(alignment: .top, spacing: 40) {
            LivePicture(player: model.livePlayer)
                .frame(width: 427, height: 240)
                .background(.black)
                .clipShape(.rect(cornerRadius: 16))
                .overlay {
                    if preview.failed { Text("Preview unavailable").font(.callout).foregroundStyle(.secondary) }
                }
            VStack(alignment: .leading, spacing: 10) {
                if let channel {
                    Text("\(channel.number.formatted()) \(channel.name)").font(.callout).foregroundStyle(.secondary)
                }
                if let program = preview.program {
                    Text(program.title).font(.title3).lineLimit(1)
                    Text(times(program)).font(.callout).foregroundStyle(.secondary)
                    if let summary = model.core.summary(program: program), !summary.isEmpty {
                        Text(summary).font(.callout).lineLimit(3)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(height: 240)
    }

    private func times(_ program: Program) -> String {
        let start = model.core.start(of: program)
        return (start..<max(model.core.end(of: program), start)).formatted(.interval.weekday().hour().minute())
    }
}

/// A player's picture, without controls: the guide's preview and the multiview.
struct LivePicture: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) {
        view.playerLayer.player = player
    }

    final class PlayerLayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }

        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

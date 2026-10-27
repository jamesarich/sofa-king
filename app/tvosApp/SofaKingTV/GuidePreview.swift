import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// The guide's preview, as on the other apps: the focused channel plays muted after 350 ms, its sound comes on after
/// 5 seconds of rest (unless Settings turns preview sound off), and after the rest Settings sets (10 seconds unless
/// changed, or never) it opens full screen through `open`, continuing the preview's stream and session. Each move
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
    @ObservationIgnored var open: (String) -> Void = { _ in }
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
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            await self?.tune(channelId)
        }
    }

    private func tune(_ channelId: String) async {
        guard let model else { return }
        do {
            let stream = try await model.core.previewChannel(channelId: channelId)
            guard active, self.channelId == channelId, let url = URL(string: stream.url) else { return }
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
        let openAfterMs = model.settings?.autoOpenDelay.afterMs?.int64Value
        resting = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled, let self else { return }
            // Music playing keeps the preview silent; the full screen ends the music.
            if sound && self.playingId == channelId && (model.music.queue == nil || model.music.paused) {
                model.livePlayer.isMuted = false
            }
            guard let openAfterMs else { return }
            try? await Task.sleep(for: .milliseconds(max(openAfterMs - 5000, 0)))
            guard !Task.isCancelled, self.active, self.channelId == channelId, self.playingId == channelId,
                  !self.failed, self.suppressed != channelId, !UIAccessibility.isVoiceOverRunning else { return }
            self.open(channelId)
        }
    }
}

/// The band over the guide: the preview's picture beside the focused program's channel, title, times and summary.
struct PreviewBand: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    let preview: GuidePreview

    var body: some View {
        let channel = preview.channelId.flatMap { id in lineup.channels.first { $0.guideId == id } }
        HStack(alignment: .top, spacing: 40) {
            PreviewVideo(player: model.livePlayer)
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

/// The live player's picture, without controls.
private struct PreviewVideo: UIViewRepresentable {
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

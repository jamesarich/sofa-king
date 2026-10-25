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
    /// Where a `sofaking://vod?t=` link starts the item, in milliseconds; negative for none.
    let startMs: Int64

    init(item: MediaItem, fromStart: Bool, startMs: Int64 = -1) {
        source = .item(item)
        self.fromStart = fromStart
        self.startMs = startMs
    }

    init(recording: DvrRecording, fromStart: Bool) {
        source = .recording(recording)
        self.fromStart = fromStart
        startMs = -1
    }

    /// The library item, or nil for a recording.
    var libraryItem: MediaItem? {
        if case .item(let item) = source { return item }
        return nil
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
/// to its end; inside a break the player offers Skip break, inside an episode's intro, Skip intro, and inside its
/// credits, Skip credits, or Next episode when they run to the end. The position is saved as it plays and when it stops; leaving releases the session, and backgrounding
/// releases it until the app returns, when the item reopens where it was. Reaching the end of an episode offers the
/// next one, played after a countdown; reaching the end of anything else returns to the page. For a library item,
/// the transport bar's Audio and Subtitles menus pick a track: another audio track plays the item again from where it
/// is, and subtitles are drawn over the picture from the item's position. The transport bar's Sleep timer leaves the
/// player when it runs out, or as the item ends in place of Up next; after two episodes in a row that Up next played
/// by itself, with no pause or press between, the card asks "Still watching?" and leaves after 30 seconds unanswered.
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
    /// Ends short of the item since playback last moved on, and where the last one was; see `endedEarly`.
    @State private var earlyEnds = 0
    @State private var earlyEndAtMs: Int64 = 0
    @State private var backgrounded = false
    @State private var next: MediaItem?
    /// The Up next card asks "Still watching?" and leaves when it runs out, rather than playing `next`.
    @State private var stillWatching = false
    @State private var detail: MediaItemDetail?
    @State private var detailWatch: Watch?
    @State private var audioIndex: Int32?
    @State private var subtitleIndex: Int32?
    @State private var subtitles: Subtitles?
    @State private var subtitleText: String?
    /// Where the last tick found the player in the item, so a break is skipped only when playback runs into it.
    @State private var lastPositionMs: Int64 = 0
    /// The end of the commercial break the player is in, for Skip break; nil outside one.
    @State private var breakEndMs: Int64?
    /// The end of the episode's intro while the player is in it, for Skip intro; nil outside it.
    @State private var introEndMs: Int64?
    /// With intros or credits skipped automatically: the segment end the countdown is for, offered once per segment,
    /// and the seconds left on it; nil when it waits for a press. Pausing stops it.
    @State private var autoSkipEndMs: Int64?
    @State private var autoSkipSeconds: Int?
    /// The end of the episode's credits while the player is in them, for Skip credits; nil outside them.
    @State private var creditsEndMs: Int64?
    /// The episode after this one, looked up once as the credits start, for Next episode.
    @State private var creditsNext: MediaItem?
    @State private var creditsNextAsked = false
    /// The server has every converter busy, and `waitForConverter` is asking it again.
    @State private var waitingForConverter = false

    var body: some View {
        ZStack {
            VodController(player: player, menus: trackMenus + [model.sleepMenu(endTitle: "End of this one", endsAt: nil)], actions: contextualActions).ignoresSafeArea()
            if let subtitleText {
                Text(subtitleText)
                    .font(.title3)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.6), in: .rect(cornerRadius: 8))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 60)
                    .allowsHitTesting(false)
            }
            if waitingForConverter && failure == nil {
                Label("Waiting for a free converter", systemImage: "hourglass")
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(.thinMaterial, in: .capsule)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .padding(60)
                    .allowsHitTesting(false)
            }
            if let failure {
                VStack(spacing: 24) {
                    Text(failure)
                    Button("Try again") { Task { await open() } }
                }
                .padding()
                .background(.thinMaterial, in: .rect(cornerRadius: 16))
            }
            if let next {
                UpNextCard(next: next, stillWatching: stillWatching, onPlay: { pressed in
                    if pressed { model.autoplays = 0 } else { model.autoplays += 1 }
                    play(next)
                }, onCancel: { dismiss() })
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(60)
            }
        }
        .task { await open() }
        .onAppear {
            // A renewal that finds the stream replaced hands back the new URL for the same item.
            replaced = model.core.watchReplaced { s in
                if s.itemId == request.id { load(s) }
            }
            // A library item's tracks, for the Audio and Subtitles menus; a recording has none to pick.
            if let item = request.libraryItem {
                detailWatch = model.core.watchItem(id: item.id) { state in
                    guard let ready = state as? ItemDetailStateReady else { return }
                    if detail == nil {
                        // The tracks picked in this show start its episodes, as playItem opened the stream with them.
                        let choice = model.core.trackChoice(item: item)
                        audioIndex = TrackPicksKt.initialAudio(detail: ready.detail, choice: choice).map { $0.index }
                        subtitleIndex = TrackPicksKt.initialSubtitle(detail: ready.detail, choice: choice).map { $0.index }
                    }
                    detail = ready.detail
                }
            }
        }
        .onDisappear {
            replaced?.cancel()
            detailWatch?.cancel()
            itemObservers.removeAll()
            if !ended && stream != nil { stopped() }
            // Up next swaps the cover to the next item; anything else leaves the player, and the timer with it.
            if model.vod == nil { model.sleep = SleepTimer() }
            player.replaceCurrentItem(with: nil)
            model.core.stopWatching()
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                tick()
            }
        }
        .task {
            // The subtitle on screen follows the item's position four times a second.
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                subtitleText = currentSubtitle()
            }
        }
        .task(id: subtitleIndex) {
            subtitles = nil
            // A picture subtitle is drawn into the stream by the server; only text is drawn here.
            guard let index = subtitleIndex, !isPicture(index) else { return }
            guard let item = request.libraryItem else { return }
            subtitles = try? await model.core.subtitles(itemId: item.id, index: index)
            if subtitles == nil && !Task.isCancelled { subtitleIndex = nil }
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
                        if let s = try await waitForConverter({ try await model.core.reopen() }) { load(s) } else { await open() }
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
            let s: PlaybackStream
            switch request.source {
            case .item(let item):
                s = try await waitForConverter { try await model.core.playItem(item: item, fromStart: request.fromStart, startMs: request.startMs) }
            case .recording(let recording):
                s = try await waitForConverter { try await model.core.playRecording(recording: recording, fromStart: request.fromStart) }
            }
            // Left while the session opened: the view is gone, and leaving released the session.
            guard !Task.isCancelled else { return }
            load(s)
        } catch {
            guard !Task.isCancelled else { return }
            failure = error.localizedDescription
        }
    }

    /// Runs `open`, asking again every 5 seconds for up to 20 while the server answers that every converter is busy, as
    /// the other apps do, and then throws as `open` did; any other failure throws at once.
    private func waitForConverter<T>(_ open: () async throws -> T) async throws -> T {
        defer { waitingForConverter = false }
        var waited = 0
        while true {
            do {
                return try await open()
            } catch let error where waited < 20 && converterBusy(error) {
                waitingForConverter = true
                try await Task.sleep(for: .seconds(5))
                waited += 5
            }
        }
    }

    private func converterBusy(_ error: Error) -> Bool {
        ((error as NSError).userInfo["KotlinException"] as? KotlinThrowable).map { model.core.isConverterBusy(error: $0) } ?? false
    }

    /// Plays `s`, titled for the transport bar; once it is ready it seeks to the asked-for point (or `startMs`).
    private func load(_ s: PlaybackStream, at startMs: Int64? = nil) {
        guard let url = URL(string: s.url) else { return }
        let start = startMs ?? s.startAtMs
        stream = s
        positionMs = start
        durationMs = s.itemDurationMs?.int64Value ?? 0
        let item = AVPlayerItem(url: url)
        item.externalMetadata = metadata()
        item.interstitialTimeRanges = breaks.map { b in
            let start = CMTime(value: b.startMs - s.offsetMs, timescale: 1000)
            return AVInterstitialTimeRange(timeRange: CMTimeRange(start: start, duration: CMTime(value: b.endMs - b.startMs, timescale: 1000)))
        }
        lastPositionMs = start
        // A stream the server is still converting is an HLS event playlist, which AVPlayer joins at its live edge, so
        // even a start at the stream's beginning seeks there.
        let seekMs = max(start - s.offsetMs, 0)
        var sought = false
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
            NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item).sink { _ in
                if durationMs > 0 && positionMs + earlyEndMs < durationMs { endedEarly() } else { finished() }
            },
            NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item).sink { _ in endedEarly() },
        ]
        player.replaceCurrentItem(with: item)
        player.play()
    }

    /// The position in the item, saved on the server as it plays; core decides how often it writes.
    private func tick() {
        if model.sleep.due() {
            model.sleep = SleepTimer()
            dismiss()
            return
        }
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
        breakEndMs = skips.commercialBreaks == .off ? nil : breaks.first(where: { positionMs >= $0.startMs && positionMs < $0.endMs })?.endMs
        introEndMs = skips.intros == .off ? nil
            : detail?.intro.flatMap { positionMs >= $0.startMs && positionMs < $0.endMs ? $0.endMs : nil }
        creditsEndMs = skips.credits == .off ? nil
            : detail?.credits.flatMap { positionMs >= $0.startMs && positionMs < $0.endMs ? $0.endMs : nil }
        countDownSkip(in: s)
        if creditsEndMs != nil, !creditsNextAsked, let item = request.libraryItem {
            creditsNextAsked = true
            Task { creditsNext = try? await model.core.upNext(item: item) }
        }
        guard player.rate > 0 else {
            // A pause is the viewer's doing, so Up next starts its count again.
            model.autoplays = 0
            return
        }
        switch request.source {
        case .item(let item): model.core.itemProgress(itemId: item.id, positionMs: positionMs, durationMs: durationMs)
        case .recording(let recording): model.core.recordingProgress(recordingId: recording.id, positionMs: positionMs)
        }
    }

    /// How the viewer chose to meet breaks, intros and credits.
    private var skips: Skips { model.settings?.skips ?? Skips(intros: .button, credits: .button, commercialBreaks: .button) }

    /// Skips the intro, or the credits, after 5 seconds' countdown on its button, once each, while it plays and the
    /// viewer chose to skip it automatically; a pause stops it.
    private func countDownSkip(in s: PlaybackStream) {
        let intro = introEndMs.flatMap { skips.intros == .auto_ ? $0 : nil }
        let credits = creditsEndMs.flatMap { skips.credits == .auto_ ? $0 : nil }
        guard let end = intro ?? credits else {
            autoSkipSeconds = nil
            return
        }
        if player.rate == 0 { autoSkipSeconds = nil }
        if autoSkipEndMs != end {
            guard player.rate > 0 else { return }
            autoSkipEndMs = end
            autoSkipSeconds = 5
        } else if let left = autoSkipSeconds {
            if left > 1 {
                autoSkipSeconds = left - 1
            } else {
                autoSkipSeconds = nil
                introEndMs = nil
                creditsEndMs = nil
                jump(to: end, in: s)
            }
        }
    }

    /// The recording's commercial breaks; a library item has none.
    private var breaks: [CommercialBreak] {
        if case .recording(let recording) = request.source { return recording.breaks }
        return []
    }

    /// With Skip commercials automatic, jumps to the end of a break that playback ran into between two ticks; a seek into
    /// a break stays there. A break end past what the server has converted restarts the conversion there.
    private func skipBreak(from previousMs: Int64, to nowMs: Int64, in s: PlaybackStream) {
        guard skips.commercialBreaks == .auto_, player.rate > 0,
              let b = breaks.first(where: { previousMs < $0.startMs && nowMs >= $0.startMs && nowMs < $0.endMs })
        else { return }
        jump(to: b.endMs, in: s)
    }

    /// Moves to `ms` in the item; a point past what the server has converted restarts the conversion there.
    private func jump(to ms: Int64, in s: PlaybackStream) {
        let target = CMTime(value: ms - s.offsetMs, timescale: 1000)
        let seekableEnd = player.currentItem?.seekableTimeRanges.last?.timeRangeValue.end ?? .zero
        if s.mode == "direct" || CMTimeCompare(target, seekableEnd) <= 0 {
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        } else {
            Task {
                if let restarted = try? await model.core.restartItemAt(positionMs: ms) { load(restarted) }
            }
        }
    }

    /// Skip break while the player is in a commercial break, Skip intro while it is in an episode's intro, and Skip
    /// credits in its credits, or Next episode when they run to the end and one follows; the system shows them over
    /// the picture.
    private var contextualActions: [UIAction] {
        guard let s = stream else { return [] }
        if let end = breakEndMs {
            return [UIAction(title: "Skip break", image: UIImage(systemName: "forward.end")) { _ in
                breakEndMs = nil
                jump(to: end, in: s)
            }]
        }
        if let end = introEndMs {
            let title = autoSkipSeconds.map { "Skip intro (\($0))" } ?? "Skip intro"
            return [UIAction(title: title, image: UIImage(systemName: "forward.end")) { _ in
                autoSkipSeconds = nil
                introEndMs = nil
                jump(to: end, in: s)
            }]
        }
        if let end = creditsEndMs {
            if let upcoming = creditsNext, durationMs > 0, end >= durationMs - 1000 {
                return [UIAction(title: "Next episode", image: UIImage(systemName: "forward.end.alt")) { _ in
                    model.autoplays = 0
                    play(upcoming)
                }]
            }
            let title = autoSkipSeconds.map { "Skip credits (\($0))" } ?? "Skip credits"
            return [UIAction(title: title, image: UIImage(systemName: "forward.end")) { _ in
                autoSkipSeconds = nil
                creditsEndMs = nil
                jump(to: end, in: s)
            }]
        }
        return []
    }

    /// The subtitle at the player's position in the item, or nil.
    private func currentSubtitle() -> String? {
        guard let subtitles, let s = stream else { return nil }
        let seconds = player.currentTime().seconds
        guard seconds.isFinite else { return nil }
        return subtitles.textAt(positionMs: Int64(seconds * 1000) + s.offsetMs)
    }

    /// The transport bar's Audio menu, when the file has more than one track, and its Subtitles menu.
    private var trackMenus: [UIMenuElement] {
        guard let detail else { return [] }
        var menus: [UIMenuElement] = []
        if detail.audio.count > 1 {
            let actions = detail.audio.enumerated().map { number, track in
                UIAction(title: TrackNamesKt.trackLabel(track: track, number: Int32(number + 1)), state: track.index == audioIndex ? .on : .off) { _ in
                    pickAudio(track.index)
                }
            }
            menus.append(UIMenu(title: "Audio", image: UIImage(systemName: "speaker.wave.2"), options: .singleSelection, children: actions))
        }
        if !detail.subtitles.isEmpty {
            var actions = [UIAction(title: "Off", state: subtitleIndex == nil ? .on : .off) { _ in pickSubtitle(nil) }]
            actions += detail.subtitles.enumerated().map { number, track in
                UIAction(title: TrackNamesKt.trackLabel(track: track, number: Int32(number + 1)), state: track.index == subtitleIndex ? .on : .off) { _ in
                    pickSubtitle(track.index)
                }
            }
            menus.append(UIMenu(title: "Subtitles", image: UIImage(systemName: "captions.bubble"), options: .singleSelection, children: actions))
        }
        return menus
    }

    /// Plays the item again from where it is with the audio track `index`.
    private func pickAudio(_ index: Int32) {
        guard index != audioIndex else { return }
        audioIndex = index
        rememberTracks()
        restream()
    }

    /// Shows subtitle track `index`, or none: text is drawn here over the same stream, pictures need a new one.
    private func pickSubtitle(_ index: Int32?) {
        guard index != subtitleIndex else { return }
        let picture = index.map(isPicture) == true || subtitleIndex.map(isPicture) == true
        subtitleIndex = index
        rememberTracks()
        if picture { restream() }
    }

    /// Keeps the picked tracks for the show, so its next episodes start with them.
    private func rememberTracks() {
        guard let item = request.libraryItem, let detail else { return }
        model.core.rememberTracks(item: item, detail: detail, audioIndex: audioIndex ?? -1, subtitleIndex: subtitleIndex ?? -1)
    }

    /// True when subtitle track `index` is pictures (PGS, DVD), which the server draws into the picture.
    private func isPicture(_ index: Int32) -> Bool {
        detail?.subtitles.first { $0.index == index }.map { TrackPicksKt.isPictureSubtitle(track: $0) } ?? false
    }

    /// Plays the item again from where it is with the picked audio track and picture subtitle.
    private func restream() {
        guard let item = request.libraryItem else { return }
        let at = positionMs
        let audio = audioIndex ?? -1
        let picture = subtitleIndex.flatMap { isPicture($0) ? $0 : nil } ?? -1
        Task {
            do {
                load(try await waitForConverter { try await model.core.switchItemTracks(item: item, positionMs: at, audioIndex: audio, subtitleIndex: picture) })
            } catch {
                failure = error.localizedDescription
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

    /// The stream stopped well short of the item: a dropped connection or conversion rather than the end, so it plays
    /// again from where it stopped, up to twice until playback moves on by 30 seconds, and then fails, so Up next never
    /// plays over an episode cut short.
    private func endedEarly() {
        if positionMs >= earlyEndAtMs + 30_000 { earlyEnds = 0 }
        earlyEndAtMs = positionMs
        earlyEnds += 1
        guard earlyEnds <= 2 else {
            failure = "The item stopped playing"
            return
        }
        let at = positionMs
        Task {
            do {
                if let restarted = try await waitForConverter({ try await model.core.restartItemAt(positionMs: at) }) { load(restarted, at: at) } else { await open() }
            } catch {
                failure = error.localizedDescription
            }
        }
    }

    private func finished() {
        guard !ended else { return }
        ended = true
        let end = durationMs > 0 ? durationMs : positionMs
        report(stoppedAt: end)
        if model.sleep.atEnd {
            model.sleep = SleepTimer()
            dismiss()
            return
        }
        guard let item = request.libraryItem else {
            dismiss()
            return
        }
        Task {
            if let upNext = try? await model.core.upNext(item: item) {
                stillWatching = model.autoplays >= stillWatchingAfter
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

/// The Up next card once an episode ends: the next one's still, number and title, counting down to playing it.
/// Play now or the countdown plays it; Cancel or Menu returns to the page.
private struct UpNextCard: View {
    let next: MediaItem
    /// Asks "Still watching?": the count runs 30 seconds and then leaves, and Keep watching plays `next`.
    let stillWatching: Bool
    /// Plays `next`; true when the viewer pressed for it, false when the count ran out.
    let onPlay: (Bool) -> Void
    let onCancel: () -> Void
    @State private var secondsLeft = 0
    @FocusState private var playFocused: Bool

    private var seconds: Int { stillWatching ? 30 : 10 }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(stillWatching ? "Still watching?" : "Up next").font(.headline).foregroundStyle(.tint)
            Artwork(url: next.backdrop ?? next.poster, title: next.title)
                .frame(width: 480, height: 270)
                .clipShape(.rect(cornerRadius: 12))
            if let subtitle = [next.showTitle, next.episodeLabel].compactMap({ $0 }).joined(separator: " · ").nilIfEmpty {
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Text(next.title).font(.title3).lineLimit(2)
            ProgressView(value: Double(seconds - secondsLeft), total: Double(seconds))
            HStack(spacing: 24) {
                if stillWatching {
                    Button("Keep watching", systemImage: "play.fill") { onPlay(true) }.focused($playFocused)
                    Button("Stop (\(secondsLeft))", action: onCancel)
                } else {
                    Button("Play now (\(secondsLeft))", systemImage: "play.fill") { onPlay(true) }.focused($playFocused)
                    Button("Cancel", action: onCancel)
                }
            }
        }
        .frame(width: 480)
        .padding(32)
        .background(.thinMaterial, in: .rect(cornerRadius: 24))
        .onAppear { playFocused = true }
        .onExitCommand(perform: onCancel)
        .task {
            secondsLeft = seconds
            while secondsLeft > 0 {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                secondsLeft -= 1
            }
            if stillWatching { onCancel() } else { onPlay(false) }
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// The system player UI, which handles the Siri Remote's play, pause, scrubbing and Menu, with the app's track menus
/// and the speeds of the other apps' Speed button in its transport bar, and Skip break over the picture.
private struct VodController: UIViewControllerRepresentable {
    let player: AVPlayer
    let menus: [UIMenuElement]
    let actions: [UIAction]

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.speeds = [1, 1.25, 1.5, 2].map { AVPlaybackSpeed(rate: $0, localizedName: "\($0.formatted())×") }
        controller.transportBarCustomMenuItems = menus
        controller.contextualActions = actions
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.transportBarCustomMenuItems = menus
        controller.contextualActions = actions
    }
}

/// How far short of the item's length an end may come and still be the end: a conversion can stop a little short.
private let earlyEndMs: Int64 = 10_000

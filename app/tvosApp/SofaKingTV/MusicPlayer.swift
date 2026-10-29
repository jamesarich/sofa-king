import AVFoundation
import Combine
import MediaPlayer
import SwiftUI
import SofaKingCore

/// The music the app plays apart from any page: core's queue, and each song's session, opened through core and played
/// on its own AVPlayer, so a song plays on while the viewer browses, or leaves the app, and the next opens when it
/// ends. A channel, a movie or a recording playing ends it (`stop`). The system's Now Playing shows the song, and its
/// play, pause, next and previous reach it, as the remote's do.
@MainActor
@Observable
final class MusicPlayer {
    private(set) var queue: MusicQueue?
    private(set) var paused = false
    private(set) var failure: String?
    private(set) var positionMs: Int64 = 0
    private(set) var durationMs: Int64 = 0
    /// Whether the household likes the song playing; the heart on Now playing and the system's Like show it.
    private(set) var liked = false

    private let core: SofaKingTv
    private let player = AVPlayer()
    @ObservationIgnored private var observers: [AnyCancellable] = []
    @ObservationIgnored private var opening: Task<Void, Never>?
    /// Counts the songs opened, so a session that opens after the viewer moved on is let go of.
    @ObservationIgnored private var opened = 0
    @ObservationIgnored private var stream: PlaybackStream?
    @ObservationIgnored private var ticker: Any?
    @ObservationIgnored private var commandsAdded = false
    /// Whether the song playing was told to the server as played, which it is once per start.
    @ObservationIgnored private var counted = false

    init(core: SofaKingTv) {
        self.core = core
    }

    /// The song playing, or nil while no music is.
    var song: MediaItem? { queue?.current }

    var repeatMode: Repeat { queue.map { core.repeatOf(queue: $0) } ?? .off }

    /// Plays `queue` from its current song.
    func play(_ queue: MusicQueue) {
        self.queue = queue
        counted = false
        open()
    }

    func togglePause() {
        guard song != nil else { return }
        if failure != nil {
            open(at: positionMs)
            return
        }
        paused.toggle()
        if paused { player.pause() } else { player.play() }
        publishInfo()
    }

    /// The next song, or the end of the music after the last when nothing repeats.
    func next(ended: Bool = false) {
        guard let queue else { return }
        if let next = queue.next(ended: ended) { play(next) } else { stop() }
    }

    /// Back to the start of the song once it is a few seconds in, else the song before.
    func previous() {
        guard let queue else { return }
        play(positionMs > restartMs ? queue : queue.previous())
    }

    func toggleShuffle() {
        guard let queue else { return }
        self.queue = core.shuffleQueue(queue: queue, on: !queue.shuffled)
    }

    /// Off, then the whole queue, then the one song.
    func nextRepeat() {
        queue = queue?.nextRepeat()
    }

    /// Ends the music and lets go of its session, leaving any other stream's alone.
    func stop() {
        guard let queue else { return }
        opening?.cancel()
        opened += 1
        self.queue = nil
        failure = nil
        paused = false
        observers.removeAll()
        player.replaceCurrentItem(with: nil)
        if let ticker { player.removeTimeObserver(ticker) }
        ticker = nil
        stream = nil
        core.releaseSong(itemId: queue.current.id)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    /// Opens the current song's session at `startMs` and plays it.
    private func open(at startMs: Int64 = 0) {
        guard let song else { return }
        opening?.cancel()
        opened += 1
        let attempt = opened
        failure = nil
        paused = false
        positionMs = startMs
        durationMs = song.durationMs?.int64Value ?? 0
        liked = song.favorite
        MPRemoteCommandCenter.shared().likeCommand.isActive = liked
        addCommands()
        publishInfo()
        opening = Task {
            do {
                let s = try await core.playSong(song: song, startMs: startMs)
                guard attempt == opened else {
                    core.releaseSong(itemId: s.itemId ?? song.id)
                    return
                }
                load(s, at: startMs)
            } catch {
                guard attempt == opened else { return }
                failure = error.localizedDescription
            }
        }
    }

    private func load(_ s: PlaybackStream, at startMs: Int64) {
        guard let url = URL(string: s.url) else { return }
        stream = s
        if let known = s.itemDurationMs?.int64Value, known > 0 { durationMs = known }
        // Music plays on behind the app, and over the silent switch other apps' sound would leave it alone.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        let item = AVPlayerItem(url: url)
        let seekMs = max(max(startMs, s.startAtMs) - s.offsetMs, 0)
        var sought = seekMs == 0
        observers = [
            item.publisher(for: \.status).filter { $0 == .readyToPlay }.sink { [weak self] _ in
                guard let self, !sought else { return }
                sought = true
                self.player.seek(to: CMTime(value: seekMs, timescale: 1000))
            },
            item.publisher(for: \.status).filter { $0 == .failed }.sink { [weak self] _ in
                self?.failure = item.error?.localizedDescription ?? "The song stopped playing"
            },
            NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item).sink { [weak self] _ in
                self?.next(ended: true)
            },
        ]
        if ticker == nil {
            ticker = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 2), queue: .main) { [weak self] time in
                MainActor.assumeIsolated { self?.tick(time) }
            }
        }
        player.replaceCurrentItem(with: item)
        player.play()
    }

    private func tick(_ time: CMTime) {
        guard let stream, time.seconds.isFinite else { return }
        positionMs = Int64(time.seconds * 1000) + stream.offsetMs
        if durationMs == 0, let length = player.currentItem?.duration.seconds, length.isFinite {
            durationMs = Int64(length * 1000) + stream.offsetMs
        }
        // A song counts as played after 30 seconds, or half of a shorter one, as in the Compose app.
        if !counted, !paused, let song, positionMs >= min(playedMs, (durationMs > 0 ? durationMs : .max) / 2) {
            counted = true
            core.played(itemId: song.id)
        }
    }

    /// Likes or unlikes the song playing for the household; the heart shows the change at once and takes it back when
    /// the server does not.
    func setLiked(_ like: Bool) {
        guard let song, like != liked else { return }
        let id = song.id
        liked = like
        MPRemoteCommandCenter.shared().likeCommand.isActive = like
        Task {
            let saved = (try? await core.setFavorite(itemId: id, favorite: like))?.boolValue ?? false
            guard !saved, self.song?.id == id else { return }
            liked = !like
            MPRemoteCommandCenter.shared().likeCommand.isActive = !like
        }
    }

    /// Moves to `seconds` in the song, as the system's scrubber asks.
    private func seek(toSeconds seconds: Double) {
        guard let stream, seconds.isFinite else { return }
        let ms = max(Int64(seconds * 1000), 0)
        positionMs = ms
        player.seek(to: CMTime(value: max(ms - stream.offsetMs, 0), timescale: 1000))
        publishInfo()
    }

    /// The song's names, length, position and cover in the system's Now Playing.
    private func publishInfo() {
        guard let song else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: paused ? 0.0 : 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let artist = song.trackArtist ?? song.artist { info[MPMediaItemPropertyArtist] = artist }
        if let album = song.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if durationMs > 0 { info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000 }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        guard let poster = song.poster, let url = URL(string: poster) else { return }
        let id = song.id
        Task {
            guard let loaded = try? await URLSession.shared.data(from: url), let image = UIImage(data: loaded.0) else { return }
            guard self.song?.id == id, var current = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
            current[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = current
        }
    }

    /// The system's play, pause, next and previous, once, for as long as the app runs.
    private func addCommands() {
        guard !commandsAdded else { return }
        commandsAdded = true
        let center = MPRemoteCommandCenter.shared()
        handle(center.playCommand) { if $0.paused { $0.togglePause() } }
        handle(center.pauseCommand) { if !$0.paused { $0.togglePause() } }
        handle(center.togglePlayPauseCommand) { $0.togglePause() }
        handle(center.nextTrackCommand) { $0.next() }
        handle(center.previousTrackCommand) { $0.previous() }
        handle(center.likeCommand) { $0.setLiked(!$0.liked) }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            MainActor.assumeIsolated {
                guard let self, self.song != nil, let event = event as? MPChangePlaybackPositionCommandEvent else {
                    return .noActionableNowPlayingItem
                }
                self.seek(toSeconds: event.positionTime)
                return .success
            }
        }
    }

    /// Runs `action` for `command` while a song is loaded; the system calls on the main thread.
    private func handle(_ command: MPRemoteCommand, _ action: @escaping @MainActor (MusicPlayer) -> Void) {
        command.addTarget { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.song != nil else { return .noActionableNowPlayingItem }
                action(self)
                return .success
            }
        }
    }
}

/// Past this far into a song, Previous starts it again rather than going back a song.
private let restartMs: Int64 = 3_000

/// A song counts as played this far in, or halfway through when it is shorter.
private let playedMs: Int64 = 30_000

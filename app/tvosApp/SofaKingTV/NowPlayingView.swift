import SwiftUI
import SofaKingCore

/// The song the music plays: its cover, title, artist and album, how far in it is, and Shuffle, Previous, Play or
/// Pause, Next and Repeat. The remote's play/pause button pauses it from anywhere on the page. Menu leaves it and the
/// music plays on; it closes by itself once the music ends. tvOS's screen saver covers it when the remote rests.
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @FocusState private var playFocused: Bool

    var body: some View {
        let music = model.music
        Group {
            if let song = music.song {
                HStack(spacing: 80) {
                    Artwork(url: song.poster, title: song.album ?? song.title)
                        .frame(width: 600, height: 600)
                        .clipShape(.rect(cornerRadius: 24))
                    controls(music, song)
                        .frame(width: 800, alignment: .leading)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            if let poster = music.song?.poster {
                Artwork(url: poster, title: "")
                    .blur(radius: 80)
                    .overlay(Color.sofaOverlayScrim)
                    .ignoresSafeArea()
            }
        }
        .onPlayPauseCommand { music.togglePause() }
        .onChange(of: music.song == nil, initial: true) { _, ended in
            if ended { dismiss() }
        }
        .onAppear { playFocused = true }
    }

    private func controls(_ music: MusicPlayer, _ song: MediaItem) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("Now playing").font(.headline).foregroundStyle(.tint)
            Text(song.title).font(.title).bold().lineLimit(2)
            if let line = song.songLine {
                Text(line).font(.title3).foregroundStyle(.secondary).lineLimit(1)
            }
            if let failure = music.failure {
                Text(failure).foregroundStyle(Color.sofaError)
            }
            if music.durationMs > 0 {
                ProgressView(value: Double(min(music.positionMs, music.durationMs)), total: Double(music.durationMs))
                HStack {
                    Text(playTime(music.positionMs))
                    Spacer()
                    Text(playTime(music.durationMs))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            HStack(spacing: 32) {
                let shuffled = music.queue?.shuffled == true
                Button { music.toggleShuffle() } label: {
                    Image(systemName: "shuffle").foregroundStyle(shuffled ? Color.accentColor : Color.primary)
                }
                    .accessibilityLabel("Shuffle")
                    .accessibilityValue(shuffled ? "On" : "Off")
                Button { music.previous() } label: { Image(systemName: "backward.fill") }
                    .accessibilityLabel("Previous")
                Button { music.togglePause() } label: {
                    Image(systemName: music.paused || music.failure != nil ? "play.fill" : "pause.fill")
                }
                .focused($playFocused)
                .accessibilityLabel(music.paused || music.failure != nil ? "Play" : "Pause")
                Button { music.next() } label: { Image(systemName: "forward.fill") }
                    .accessibilityLabel("Next")
                let mode = music.repeatMode
                Button { music.nextRepeat() } label: {
                    Image(systemName: mode == .one ? "repeat.1" : "repeat")
                        .foregroundStyle(mode == .off ? Color.primary : Color.accentColor)
                }
                    .accessibilityLabel("Repeat")
                    .accessibilityValue(mode == .off ? "Off" : mode == .all ? "All" : "One")
            }
            .padding(.top, 16)
        }
    }
}

extension MediaItem {
    /// A song's artist and album, as one line.
    var songLine: String? {
        let parts = [trackArtist ?? artist, album].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

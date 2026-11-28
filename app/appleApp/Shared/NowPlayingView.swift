import SwiftUI
import SofaKingCore

/// The song the music plays: its cover, title, artist and album, how far in it is, and Shuffle, Previous, Play or
/// Pause, Next and Repeat. The remote's play/pause button pauses it from anywhere on the page. Menu, or the chevron on
/// an iPhone or iPad, leaves it and the music plays on; it closes by itself once the music ends. On an iPhone or iPad
/// the cover sits above the controls in portrait and beside them in landscape. tvOS's screen saver covers it when the
/// remote rests.
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @FocusState private var playFocused: Bool
    @PageLayout private var metrics

    var body: some View {
        let music = model.music
        Group {
            if let song = music.song {
                #if os(iOS)
                touchLayout(music, song)
                #else
                HStack(spacing: 80) {
                    Artwork(url: song.poster, title: song.album ?? song.title)
                        .frame(width: 600, height: 600)
                        .clipShape(.rect(cornerRadius: 24))
                    controls(music, song)
                        .frame(width: 800, alignment: .leading)
                }
                #endif
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

    #if os(iOS)
    /// The cover above the controls in portrait and beside them in landscape, as large as the screen leaves it, with a
    /// chevron at the top that closes the page.
    private func touchLayout(_ music: MusicPlayer, _ song: MediaItem) -> some View {
        GeometryReader { geometry in
            let portrait = geometry.size.height > geometry.size.width
            let room = portrait
                ? min(geometry.size.width - 2 * metrics.margin, geometry.size.height * 0.45)
                : min(geometry.size.height - 2 * metrics.margin, geometry.size.width * 0.4)
            let side = max(min(room, 480), 0)
            let layout = portrait ? AnyLayout(VStackLayout(spacing: 32)) : AnyLayout(HStackLayout(spacing: 40))
            layout {
                Artwork(url: song.poster, title: song.album ?? song.title)
                    .frame(width: side, height: side)
                    .clipShape(.rect(cornerRadius: 16))
                controls(music, song)
                    .frame(maxWidth: 480, alignment: .leading)
            }
            .padding(metrics.margin)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .overlay(alignment: .topLeading) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.down").padding(12).background(.thinMaterial, in: .circle)
            }
            .accessibilityLabel("Close")
            .accessibilityIdentifier("now-playing-close")
            .padding(.horizontal, metrics.margin)
        }
    }
    #endif

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
            HStack(spacing: metrics.touch ? 0 : 32) {
                let shuffled = music.queue?.shuffled == true
                Button { music.toggleShuffle() } label: {
                    Image(systemName: "shuffle").foregroundStyle(shuffled ? Color.accentColor : Color.primary).touchTarget()
                }
                    .accessibilityLabel("Shuffle")
                    .accessibilityValue(shuffled ? "On" : "Off")
                Button { music.previous() } label: { Image(systemName: "backward.fill").touchTarget() }
                    .accessibilityLabel("Previous")
                Button { music.togglePause() } label: {
                    Image(systemName: music.paused || music.failure != nil ? "play.fill" : "pause.fill").touchTarget()
                }
                .focused($playFocused)
                .accessibilityLabel(music.paused || music.failure != nil ? "Play" : "Pause")
                Button { music.next() } label: { Image(systemName: "forward.fill").touchTarget() }
                    .accessibilityLabel("Next")
                let mode = music.repeatMode
                Button { music.nextRepeat() } label: {
                    Image(systemName: mode == .one ? "repeat.1" : "repeat")
                        .foregroundStyle(mode == .off ? Color.primary : Color.accentColor)
                        .touchTarget()
                }
                    .accessibilityLabel("Repeat")
                    .accessibilityValue(mode == .off ? "Off" : mode == .all ? "All" : "One")
                Button { music.setLiked(!music.liked) } label: {
                    Image(systemName: music.liked ? "heart.fill" : "heart")
                        .foregroundStyle(music.liked ? Color.accentColor : Color.primary)
                        .touchTarget()
                }
                    .accessibilityLabel(music.liked ? "Remove from favorites" : "Add to favorites")
            }
            #if os(iOS)
            .font(.title2)
            .buttonStyle(.borderless)
            #endif
            .padding(.top, 16)
        }
    }
}

private extension View {
    /// On an iPhone or iPad, an icon shares the row evenly with the others and takes a fingertip's height.
    @ViewBuilder
    func touchTarget() -> some View {
        #if os(iOS)
        frame(maxWidth: .infinity, minHeight: 44).contentShape(.rect)
        #else
        self
        #endif
    }
}

extension MediaItem {
    /// A song's artist and album, as one line.
    var songLine: String? {
        let parts = [trackArtist ?? artist, album].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

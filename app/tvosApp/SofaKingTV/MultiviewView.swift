import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// Multiview, on a server that draws it: a list of channels with the one being watched ticked, then the favorites,
/// the recent channels and the rest; tick two to four and choose Watch. The server draws them into one picture, two
/// side by side or three or four in a grid, and only the tile with the white border plays its sound. The arrows move
/// the sound to the next tile that way, and the touch surface's click watches that channel full screen through
/// `watch`. Names show for 5 seconds after each press. Menu returns to the list, and Menu from the list to the
/// channel the multiview was opened on.
struct MultiviewView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let from: Channel
    let watch: (Channel) -> Void
    @State private var picked: [String]
    @State private var watching = false
    @State private var audio = 0
    @State private var player = AVPlayer()
    @State private var itemObservers: [AnyCancellable] = []
    @State private var failure: String?
    @State private var opening: Task<Void, Never>?
    @State private var namesShown = false
    @State private var namesTimer: Task<Void, Never>?

    private static let maxTiles = 4

    init(from: Channel, watch: @escaping (Channel) -> Void) {
        self.from = from
        self.watch = watch
        _picked = State(initialValue: [from.guideId])
    }

    var body: some View {
        Group {
            if watching { picture } else { list }
        }
        .onDisappear {
            opening?.cancel()
            namesTimer?.cancel()
            itemObservers.removeAll()
            player.replaceCurrentItem(with: nil)
        }
        .onChange(of: scenePhase) { _, phase in
            guard watching else { return }
            switch phase {
            case .background:
                opening?.cancel()
                player.replaceCurrentItem(with: nil)
                Task { try? await model.core.release() }
            case .active where player.currentItem == nil:
                openMultiview()
            default:
                break
            }
        }
    }

    private var channels: [Channel] { model.lineup?.channels ?? [] }

    /// The picked channels in the order they were picked, then the favorites, the recent ones and the rest, each once.
    private var choices: [Channel] {
        let byId = Dictionary(channels.map { ($0.guideId, $0) }, uniquingKeysWith: { first, _ in first })
        let favorites = (model.settings?.favorites ?? []).compactMap { $0 as? String }
        var seen = Set<String>()
        return (picked + favorites + model.recentChannels.map(\.guideId) + channels.map(\.guideId))
            .filter { seen.insert($0).inserted }
            .compactMap { byId[$0] }
    }

    private var list: some View {
        NavigationStack {
            List {
                Section {
                    Button("Watch", systemImage: "rectangle.split.2x2") { start() }.disabled(picked.count < 2)
                } footer: {
                    Text("Pick two to four channels. The server draws them into one picture; only one plays its sound.")
                }
                Section {
                    ForEach(choices, id: \.guideId) { channel in
                        let ticked = picked.contains(channel.guideId)
                        Button { toggle(channel.guideId) } label: {
                            Label("\(channel.number.formatted()) \(channel.name)", systemImage: ticked ? "checkmark.circle.fill" : "circle")
                        }
                        .disabled(!ticked && picked.count >= Self.maxTiles)
                    }
                }
            }
            .navigationTitle("Multiview")
        }
        .onExitCommand { dismiss() }
    }

    private var picture: some View {
        Button { watchTile() } label: {
            ZStack {
                LivePicture(player: player).background(.black)
                if namesShown { names }
                if let failure {
                    Text(failure).padding().background(.thinMaterial, in: .rect(cornerRadius: 16))
                }
            }
            .ignoresSafeArea()
        }
        .buttonStyle(BareButtonStyle())
        .ignoresSafeArea()
        .onMoveCommand { direction in move(direction) }
        .onExitCommand { back() }
    }

    /// Each tile's channel name, low on its left, with a speaker by the one whose sound plays.
    private var names: some View {
        GeometryReader { geometry in
            ForEach(Array(picked.enumerated()), id: \.offset) { entry in
                let index = entry.offset
                let guideId = entry.element
                let frame = Self.tile(index, of: picked.count, in: geometry.size)
                let name = channels.first { $0.guideId == guideId }?.name ?? ""
                Label(name, systemImage: index == audio ? "speaker.wave.2.fill" : "speaker.slash")
                    .font(.callout)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.thinMaterial, in: .capsule)
                    .position(x: frame.minX + 40 + 160, y: frame.maxY - 60)
            }
        }
    }

    /// Where tile `index` of `count` sits, as the server draws it: two side by side across the middle, three or four
    /// in a grid, left to right and top to bottom.
    static func tile(_ index: Int, of count: Int, in size: CGSize) -> CGRect {
        let left = CGFloat(index % 2) * 0.5
        let top = count == 2 ? 0.25 : CGFloat(index / 2) * 0.5
        return CGRect(x: left * size.width, y: top * size.height, width: size.width / 2, height: size.height / 2)
    }

    /// The tile an arrow moves the sound to from `from`, of `count`; `from` itself when nothing lies that way.
    static func moved(_ direction: MoveCommandDirection, from: Int, count: Int) -> Int {
        let to: Int
        switch direction {
        case .left: to = from % 2 == 1 ? from - 1 : from
        case .right: to = from % 2 == 0 ? from + 1 : from
        case .up: to = count > 2 && from >= 2 ? from - 2 : from
        case .down: to = count > 2 && from < 2 ? from + 2 : from
        @unknown default: to = from
        }
        return to < count ? to : from
    }

    private func toggle(_ guideId: String) {
        if let index = picked.firstIndex(of: guideId) {
            picked.remove(at: index)
        } else if picked.count < Self.maxTiles {
            picked.append(guideId)
        }
    }

    private func start() {
        guard picked.count >= 2 else { return }
        audio = 0
        watching = true
        openMultiview()
    }

    private func back() {
        opening?.cancel()
        itemObservers.removeAll()
        player.replaceCurrentItem(with: nil)
        Task { try? await model.core.release() }
        failure = nil
        watching = false
    }

    private func watchTile() {
        guard let channel = channels.first(where: { $0.guideId == picked[safe: audio] }) else { return }
        watch(channel)
        dismiss()
    }

    private func move(_ direction: MoveCommandDirection) {
        flashNames()
        let to = Self.moved(direction, from: audio, count: picked.count)
        guard to != audio else { return }
        audio = to
        guard failure == nil, player.currentItem != nil else {
            openMultiview()
            return
        }
        opening?.cancel()
        opening = Task {
            do {
                if let stream = try await model.core.moveMultiviewAudio(tile: Int32(to)) {
                    try await play(stream)
                } else {
                    openMultiview()
                }
            } catch {
                guard !Task.isCancelled else { return }
                fail(error.localizedDescription)
            }
        }
    }

    private func openMultiview() {
        opening?.cancel()
        failure = nil
        let ids = picked
        let sound = audio
        opening = Task {
            do {
                let stream = try await model.core.multiview(channelIds: ids, audio: Int32(sound))
                guard !Task.isCancelled, watching else { return }
                flashNames()
                try await play(stream)
            } catch {
                guard !Task.isCancelled else { return }
                fail(error.localizedDescription)
            }
        }
    }

    /// Plays `stream` once its playlist lists three segments.
    private func play(_ stream: PlaybackStream) async throws {
        guard let url = URL(string: stream.url) else { return }
        await StreamStart.settled(url)
        guard !Task.isCancelled, watching else { return }
        let item = AVPlayerItem(url: url)
        itemObservers = [
            item.publisher(for: \.status).filter { $0 == .failed }.sink { _ in fail("The multiview stopped playing") },
        ]
        player.replaceCurrentItem(with: item)
        player.play()
        failure = nil
    }

    /// Shows why, and asks the server again after 5 seconds, as the other apps do.
    private func fail(_ message: String) {
        failure = message
        opening?.cancel()
        opening = Task {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled, watching else { return }
            openMultiview()
        }
    }

    private func flashNames() {
        namesShown = true
        namesTimer?.cancel()
        namesTimer = Task {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            namesShown = false
        }
    }
}

/// A button that draws its label as it is, with no focus lift: the multiview's picture.
private struct BareButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

import SwiftUI
import SofaKingCore

/// Every channel with the program on now and next; selecting one plays it full screen.
struct ChannelListView: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    @State private var playing: Channel?

    var body: some View {
        List(lineup.channels, id: \.guideId) { channel in
            Button { playing = channel } label: { ChannelRow(lineup: lineup, channel: channel) }
        }
        .navigationTitle("Sofa King")
        .toolbar {
            NavigationLink { SettingsView() } label: { Label("Settings", systemImage: "gear") }
        }
        .fullScreenCover(item: $playing) { channel in
            PlayerView(channel: channel)
        }
    }
}

private struct ChannelRow: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    let channel: Channel

    var body: some View {
        let nowNext = model.core.nowAndNext(lineup: lineup, channel: channel)
        HStack(spacing: 24) {
            Text(channel.number.formatted()).monospacedDigit().frame(width: 120, alignment: .trailing)
            VStack(alignment: .leading, spacing: 4) {
                Text(channel.name).font(.headline)
                if let now = nowNext.now {
                    Text(now.title).foregroundStyle(.secondary)
                }
                if let next = nowNext.next {
                    Text("Next: \(next.title) at \(time(of: next))").font(.caption).foregroundStyle(.tertiary)
                }
            }
        }
    }

    private func time(of programme: Programme) -> String {
        Date(timeIntervalSince1970: Double(model.core.epochMs(programme: programme)) / 1000)
            .formatted(date: .omitted, time: .shortened)
    }
}

extension Channel: @retroactive Identifiable {
    public var id: String { guideId }
}

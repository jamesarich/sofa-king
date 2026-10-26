import SwiftUI
import SofaKingCore

/// Where focus is in the guide: a cell, by its channel and where its program (or its stretch without one) starts, or
/// the edge past either end of a row, which pages the window and hands focus back to a cell.
enum GuideFocus: Hashable {
    case cell(guideId: String, startMs: Int64)
    case edge(guideId: String, forward: Bool)
}

/// One cell of a row: a program, or a stretch the guide has nothing for, cut to the window's edges for its width.
struct GuideSegment: Identifiable {
    let program: Program?
    let startMs: Int64
    let endMs: Int64
    let shownStartMs: Int64
    let shownEndMs: Int64

    var id: Int64 { startMs }

    func covers(_ ms: Int64) -> Bool { startMs <= ms && ms < endMs }
}

/// The guide's window: 90 minutes at a time, from the half hour now is in, paged half an hour a press up to 24 hours
/// ahead, as the other apps' guide spans.
enum GuideWindow {
    static let step: Int64 = 30 * 60 * 1000
    static let span: Int64 = 90 * 60 * 1000
    static let reach: Int64 = 24 * 60 * 60 * 1000

    /// The start of the half hour `date` is in, on the local clock.
    static func start(at date: Date) -> Int64 {
        let ms = Int64(date.timeIntervalSince1970 * 1000)
        let offset = Int64(TimeZone.current.secondsFromGMT(for: date)) * 1000
        return ms - (ms + offset) % step
    }

    /// The row's cells from `from` to `to`: each program in order, with a cell without one wherever the guide has a
    /// gap, so every row has something to focus and play.
    static func segments(_ programs: [Program], core: SofaKingTv, from: Int64, to: Int64) -> [GuideSegment] {
        var cells: [GuideSegment] = []
        var cursor = from
        for program in programs {
            let start = core.epochMs(program: program)
            let end = core.endEpochMs(program: program)
            guard end > cursor else { continue }
            if start >= to { break }
            if start > cursor { cells.append(gap(from: cursor, to: start)) }
            let shownStart = max(start, cursor)
            cells.append(GuideSegment(program: program, startMs: start, endMs: end, shownStartMs: shownStart, shownEndMs: min(end, to)))
            cursor = end
            if cursor >= to { break }
        }
        if cursor < to { cells.append(gap(from: cursor, to: to)) }
        return cells
    }

    private static func gap(from: Int64, to: Int64) -> GuideSegment {
        GuideSegment(program: nil, startMs: from, endMs: to, shownStartMs: from, shownEndMs: to)
    }
}

/// The guide grid: a row per channel, its number and name, then its programs across 90 minutes under a row of
/// times. Selecting a program on now plays its channel; a later one opens its details. Right past a row's last cell
/// and Left past its first page the window half an hour, from now up to 24 hours ahead. Holding the touch surface on
/// a cell opens its details or stars the channel. `arrival` names a channel to focus, on now, which the grid then
/// clears: the last channel when the guide first shows, and the one just watched after the player closes.
struct GuideGrid: View {
    @Environment(AppModel.self) private var model
    let lineup: Lineup
    let channels: [Channel]
    @Binding var arrival: String?
    let play: (Channel) -> Void
    let details: (Channel, Program?) -> Void
    @State private var windowStart = GuideWindow.start(at: .now)
    @State private var now = Date()
    @FocusState private var focus: GuideFocus?

    private static let channelWidth: CGFloat = 320
    private static let edgeWidth: CGFloat = 12
    private static let rowHeight: CGFloat = 96
    private static let cellGap: CGFloat = 6

    private var earliest: Int64 { GuideWindow.start(at: now) }
    private var latest: Int64 { earliest + GuideWindow.reach - GuideWindow.span }
    private var windowEnd: Int64 { windowStart + GuideWindow.span }

    var body: some View {
        GeometryReader { geometry in
            let area = max(geometry.size.width - Self.channelWidth - 2 * Self.edgeWidth, 600)
            VStack(alignment: .leading, spacing: 12) {
                timeline(area: area)
                ScrollViewReader { proxy in
                    ScrollView(.vertical) {
                        LazyVStack(alignment: .leading, spacing: 8) {
                            ForEach(channels, id: \.guideId) { channel in
                                row(channel, area: area).id(channel.guideId)
                            }
                        }
                        .padding(.vertical, 12)
                    }
                    .onChange(of: arrival, initial: true) { _, wanted in
                        guard let wanted else { return }
                        arrive(at: wanted, proxy: proxy)
                    }
                }
            }
        }
        .onChange(of: focus) { old, new in
            guard case let .edge(guideId, forward)? = new else { return }
            var cameFromRow = false
            if case let .cell(id, _)? = old { cameFromRow = id == guideId }
            page(guideId, forward: forward, moved: cameFromRow)
        }
        .task {
            // Each minute the window catches up with now, as the other apps keep the time cursor no earlier than now.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                now = Date()
                if windowStart < earliest { windowStart = earliest }
            }
        }
    }

    /// The times along the top, one per half hour, with the day before them once the window leaves today.
    private func timeline(area: CGFloat) -> some View {
        let start = Date(timeIntervalSince1970: Double(windowStart) / 1000)
        let slots = Array(stride(from: windowStart, to: windowEnd, by: Int(GuideWindow.step)))
        return HStack(spacing: 0) {
            Text(Calendar.current.isDateInToday(start) ? "Today" : start.formatted(.dateTime.weekday(.wide)))
                .frame(width: Self.channelWidth + Self.edgeWidth, alignment: .leading)
            ForEach(slots, id: \.self) { ms in
                Text(Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .omitted, time: .shortened))
                    .frame(width: area * CGFloat(GuideWindow.step) / CGFloat(GuideWindow.span), alignment: .leading)
            }
        }
        .font(.callout)
        .foregroundStyle(.secondary)
    }

    private func row(_ channel: Channel, area: CGFloat) -> some View {
        let cells = segments(channel, from: windowStart)
        let marks = model.recordingMarks
        return HStack(spacing: 0) {
            HStack(spacing: 16) {
                Text(channel.number.formatted()).monospacedDigit().frame(width: 96, alignment: .trailing)
                Text(channel.name).lineLimit(2)
                if model.favorites.contains(channel.guideId) {
                    Image(systemName: "star.fill").foregroundStyle(.yellow).accessibilityLabel("Favorite")
                }
            }
            .font(.callout)
            .frame(width: Self.channelWidth, height: Self.rowHeight, alignment: .leading)
            edge(channel, forward: false, enabled: windowStart > earliest)
            HStack(spacing: Self.cellGap) {
                ForEach(cells) { cell in
                    let width = area * CGFloat(cell.shownEndMs - cell.shownStartMs) / CGFloat(GuideWindow.span) - Self.cellGap
                    Button { select(cell, on: channel) } label: {
                        GuideCellLabel(
                            title: cell.program?.title ?? "No program information",
                            time: cell.program.map(timeText),
                            recording: cell.program.flatMap { program in marks.map { model.core.recordingMark(marks: $0, program: program).recording } } ?? false,
                            onNow: cell.covers(Int64(now.timeIntervalSince1970 * 1000)),
                            narrow: width < 160
                        )
                    }
                    .buttonStyle(GuideCellStyle())
                    .frame(width: max(width, 8), height: Self.rowHeight)
                    .focused($focus, equals: .cell(guideId: channel.guideId, startMs: cell.startMs))
                    .accessibilityLabel("\(channel.name), \(cell.program?.title ?? "No program information")")
                    .contextMenu {
                        Button("Program details", systemImage: "info.circle") { details(channel, upcoming(cell.program)) }
                        FavoriteButton(channel: channel)
                    }
                }
            }
            .frame(width: area, alignment: .leading)
            edge(channel, forward: true, enabled: windowStart < latest)
        }
    }

    /// The clear stop past a row's end that a press moves focus onto when there is more of the guide that way.
    private func edge(_ channel: Channel, forward: Bool, enabled: Bool) -> some View {
        Color.clear
            .frame(width: Self.edgeWidth, height: Self.rowHeight)
            .focusable(enabled)
            .focused($focus, equals: .edge(guideId: channel.guideId, forward: forward))
            .accessibilityHidden(true)
    }

    private func segments(_ channel: Channel, from start: Int64) -> [GuideSegment] {
        let end = start + GuideWindow.span
        let programs = model.core.programsIn(lineup: lineup, channel: channel, fromMs: start, toMs: end)
        return GuideWindow.segments(programs, core: model.core, from: start, to: end)
    }

    /// Pages the window when focus came to the edge from its own row, and hands focus to the cell that follows the
    /// one it left; an edge reached from another row only passes focus to that row's nearest cell.
    private func page(_ guideId: String, forward: Bool, moved: Bool) {
        guard let channel = channels.first(where: { $0.guideId == guideId }) else { return }
        var start = windowStart
        let anchor: Int64
        if moved {
            anchor = forward ? windowEnd : windowStart - 1
            start = forward ? min(windowStart + GuideWindow.step, latest) : max(windowStart - GuideWindow.step, earliest)
            windowStart = start
        } else {
            anchor = forward ? windowEnd - 1 : windowStart
        }
        let cells = segments(channel, from: start)
        let target = cells.first { $0.covers(anchor) } ?? (forward ? cells.last : cells.first)
        Task { @MainActor in
            // The paged row draws first; focus then lands on a cell that exists.
            try? await Task.sleep(for: .milliseconds(50))
            focus = target.map { GuideFocus.cell(guideId: guideId, startMs: $0.startMs) }
        }
    }

    /// Scrolls to `guideId` with the window back at now and focuses what it has on now, then clears the request.
    private func arrive(at guideId: String, proxy: ScrollViewProxy) {
        now = Date()
        windowStart = earliest
        guard let channel = channels.first(where: { $0.guideId == guideId }) ?? channels.first else { return }
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let cell = segments(channel, from: windowStart).first { $0.covers(nowMs) }
        proxy.scrollTo(channel.guideId, anchor: .center)
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(100))
            if let cell { focus = .cell(guideId: channel.guideId, startMs: cell.startMs) }
            arrival = nil
        }
    }

    private func select(_ cell: GuideSegment, on channel: Channel) {
        if let program = upcoming(cell.program) {
            details(channel, program)
        } else {
            play(channel)
        }
    }

    /// `program` when it starts later, for its details; nil for one on now, whose details show what follows too.
    private func upcoming(_ program: Program?) -> Program? {
        guard let program, model.core.start(of: program) > Date() else { return nil }
        return program
    }

    private func timeText(_ program: Program) -> String {
        let start = model.core.start(of: program)
        return (start..<max(model.core.end(of: program), start)).formatted(.interval.hour().minute())
    }
}

/// What a guide cell shows: the program's title, and its times when the cell is wide enough, with a red mark when
/// it is set to record.
private struct GuideCellLabel: View {
    let title: String
    let time: String?
    let recording: Bool
    let onNow: Bool
    let narrow: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                if recording {
                    Image(systemName: "record.circle.fill").foregroundStyle(.red).accessibilityLabel("Recording")
                }
                Text(title).font(.callout.weight(onNow ? .semibold : .regular)).lineLimit(1)
            }
            if let time, !narrow {
                Text(time).font(.caption2).opacity(0.7).lineLimit(1)
            }
        }
    }
}

/// A guide cell's look: a flat tile that turns white when focused, without the system's lift, so neighbors in a
/// row never overlap.
private struct GuideCellStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        GuideCellBody(configuration: configuration)
    }
}

private struct GuideCellBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isFocused) private var focused

    var body: some View {
        configuration.label
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(focused ? Color.white : Color.white.opacity(0.08), in: .rect(cornerRadius: 12))
            .foregroundStyle(focused ? Color.black : Color.primary)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

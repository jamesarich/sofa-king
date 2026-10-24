import SwiftUI

/// The break screen, as the Compose app draws it: the crown-on-sofa mark drifting around and bouncing off the edges the
/// way an old DVD player's screensaver did, a new color on every edge and the brand colors back on a corner, with a line
/// to mark the occasion. The screen starts black, and each hit paints it the sofa's color from before the hit, a little
/// see-through so the picture faintly shows under it. `remainingMs`, when known, counts down in the middle of the
/// screen, over `quip` (the line the player dealt for this break), `watching` (the program on now), one of `facts` about
/// it at a time and the ads dodged today: `dodgedBeforeMs` before this break plus the time since `coveredSince`. The time
/// sits in the top corner. Only the mark moves, squashing against each wall it hits unless Reduce Motion is on; the
/// words stay put. It draws only; the player decides about the sound.
struct BreakScreen: View {
    let remainingMs: Int64?
    var quip = ""
    var watching: String?
    /// Facts about the program on now, each shown for `factSeconds` in turn.
    var facts: [String] = []
    var dodgedBeforeMs: Int64 = 0
    var coveredSince = Date.now

    /// The lines a break is dealt one of, as the Compose app's.
    static let quips = [
        "Hold the throne.",
        "The realm can wait.",
        "Do not adjust your sofa.",
        "Snack window open.",
        "Your show is in another castle.",
        "Cushioning you from the ads.",
        "Stretch your legs, Your Majesty.",
        "Sofa King patient.",
        "Pillow fights permitted.",
        "Recline. Unwind. Repeat.",
    ]

    /// How long each fact shows, as the Compose app's.
    static let factSeconds = 8.0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// How much of the picture the screen lets through: hardly any, so the stream only just shows.
    static let backdropOpacity = 0.9

    /// Today in the device's time zone, as `2026-10-22`: the day Ads dodged today counts in.
    static func today() -> String {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: .now)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.animation) { context in
                let markSize = min(max(min(geometry.size.width, geometry.size.height) * 0.22, 96), 240)
                let bounce = Bounce(
                    // Counted from a fixed date, so the mark carries on where it was whenever the countdown redraws the screen.
                    seconds: context.date.timeIntervalSinceReferenceDate,
                    room: CGSize(width: max(geometry.size.width - markSize, 1), height: max(geometry.size.height - markSize, 1))
                )
                // The mark's dark ink on a sofa color, the brand's mauve on black.
                let ink = bounce.backdropHue == nil ? Color(red: 0.77, green: 0.73, blue: 0.85) : SofaMark.ink
                ZStack {
                    Group {
                        if let hue = bounce.backdropHue {
                            SofaMark.lavender.hueRotation(.degrees(hue))
                        } else {
                            Color.black
                        }
                    }
                    .opacity(BreakScreen.backdropOpacity)
                    .animation(.easeInOut(duration: 0.4), value: bounce.backdropHue)
                    // The time, inside a TV's overscan and under the mark, which passes over it.
                    Text(context.date.formatted(date: .omitted, time: .shortened))
                        .font(.headline)
                        .foregroundStyle(ink.opacity(0.7))
                        .padding(.horizontal, geometry.size.width * 0.05)
                        .padding(.vertical, geometry.size.height * 0.05)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    let squashX = reduceMotion ? 1 : Bounce.squash(after: bounce.xSinceHit)
                    let squashY = reduceMotion ? 1 : Bounce.squash(after: bounce.ySinceHit)
                    SofaMark()
                        .frame(width: markSize, height: markSize)
                        .hueRotation(.degrees(bounce.hue))
                        // Squashed against the wall it hit, and as much taller or wider, so it keeps its bulk.
                        .scaleEffect(
                            x: squashX * (1 + (1 - squashY) * 0.5),
                            y: squashY * (1 + (1 - squashX) * 0.5),
                            anchor: bounce.wall
                        )
                        .position(x: bounce.x + markSize / 2, y: bounce.y + markSize / 2)
                    VStack(spacing: 6) {
                        Text(label)
                            .font(.title3)
                            .lineLimit(1)
                            .fixedSize()
                        Text(bounce.cornerSeconds.map { $0 < 4 } == true ? "Corner! You saw it. Nobody will believe you." : line)
                            .font(.callout)
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                        // The faint lines keep their place while empty, so a fact arriving or the ads dodged showing
                        // up a minute in does not move the words above them.
                        Text(watching ?? " ").font(.caption).lineLimit(1).opacity(0.7)
                        let fact = facts.isEmpty ? nil : facts[Int(max(context.date.timeIntervalSince(coveredSince), 0) / BreakScreen.factSeconds) % facts.count]
                        Text(fact ?? " ").font(.caption).lineLimit(1).opacity(0.7)
                            .contentTransition(.opacity)
                            .animation(.easeInOut(duration: 0.6), value: fact)
                        let dodgedMinutes = (dodgedBeforeMs + Int64(max(context.date.timeIntervalSince(coveredSince), 0) * 1000)) / 60_000
                        Text(dodgedMinutes > 0 ? "Ads dodged today: \(BreakScreen.hoursAndMinutes(dodgedMinutes))" : " ")
                            .font(.caption).lineLimit(1).opacity(0.7)
                    }
                    .foregroundStyle(ink)
                    .animation(.easeInOut(duration: 0.4), value: bounce.backdropHue)
                    .frame(maxWidth: geometry.size.width * 0.6)
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
            }
        }
        .ignoresSafeArea()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }

    private var label: String {
        guard let remainingMs else { return "Commercial break" }
        let seconds = Int((remainingMs + 999) / 1000)
        return "Back in \(seconds / 60):\(String(format: "%02d", seconds % 60))"
    }

    /// The line under the countdown: the break's own, or one for a break nearly over.
    private var line: String {
        guard let remainingMs, remainingMs <= 10_000 else { return quip }
        return "Places, everyone. Feet up."
    }

    /// Whole minutes as "2 h 5 min", "45 min" or "1 h", as the Compose app writes them.
    private static func hoursAndMinutes(_ minutes: Int64) -> String {
        let hours = minutes / 60
        let rest = minutes % 60
        if hours > 0 && rest > 0 { return "\(hours) h \(rest) min" }
        return hours > 0 ? "\(hours) h" : "\(rest) min"
    }
}

/// Where the mark is after `seconds`, worked out from the start rather than step by step: each axis runs back and forth
/// across its room at the same speed. Every edge it has hit moves the color on; a corner, both edges at once, brings
/// the brand colors back and its line for a few seconds. The screen behind it wears the hue the mark had before its
/// last hit.
struct Bounce {
    static let speed: Double = 72
    static let cornerSlop: Double = 6
    static let hues: [Double] = [0, 60, 120, 180, 240, 300]

    let x: Double
    let y: Double
    let hue: Double
    /// The hue the mark wore into its last hit, for the screen behind it; nil before the first.
    let backdropHue: Double?
    /// How long ago the mark hit a corner, while that corner was its last hit; nil after an edge.
    let cornerSeconds: Double?
    /// How long ago it last hit a side wall, and the top or bottom; nil before it has.
    let xSinceHit: Double?
    let ySinceHit: Double?
    /// The corner of the mark on the walls it last hit, which a squash presses toward.
    let wall: UnitPoint

    init(seconds: Double, room: CGSize) {
        let distance = Bounce.speed * seconds
        let (x, xHits, xLastHit) = Bounce.axis(distance, from: room.width * 0.3, room: room.width)
        let (y, yHits, yLastHit) = Bounce.axis(distance, from: room.height * 0.2, room: room.height)
        self.x = x
        self.y = y
        let corner = Bounce.corner(xHits: xHits, yHits: yHits, xLastHit: xLastHit, yLastHit: yLastHit)
        hue = Bounce.hue(hits: xHits + yHits, corner: corner)
        cornerSeconds = corner ? (distance - max(xLastHit, yLastHit)) / Bounce.speed : nil
        xSinceHit = xHits > 0 ? (distance - xLastHit) / Bounce.speed : nil
        ySinceHit = yHits > 0 ? (distance - yLastHit) / Bounce.speed : nil
        // An even lap heads right (or down), so it last left the left (or top) wall.
        wall = UnitPoint(x: xHits % 2 == 0 ? 0 : 1, y: yHits % 2 == 0 ? 0 : 1)
        // Before the last hit: that axis one lap back, the other as it is. An axis that has not hit yet has a
        // negative last hit, so the later one is always the axis that hit.
        if xHits + yHits == 0 {
            backdropHue = nil
        } else if xLastHit >= yLastHit {
            let before = Bounce.corner(xHits: xHits - 1, yHits: yHits, xLastHit: xLastHit - room.width, yLastHit: yLastHit)
            backdropHue = Bounce.hue(hits: xHits + yHits - 1, corner: before)
        } else {
            let before = Bounce.corner(xHits: xHits, yHits: yHits - 1, xLastHit: xLastHit, yLastHit: yLastHit - room.height)
            backdropHue = Bounce.hue(hits: xHits + yHits - 1, corner: before)
        }
    }

    /// How squashed the mark is against a wall `seconds` after hitting it: pressed in, then a quick wobble back out, as
    /// the Compose app's `squashAfter`.
    static func squash(after seconds: Double?) -> Double {
        guard let seconds, seconds >= 0, seconds < 0.6 else { return 1 }
        return 1 - 0.2 * exp(-seconds * 9) * cos(seconds * 28)
    }

    /// Whether the last hits on the two axes came close enough together to be a corner.
    private static func corner(xHits: Int, yHits: Int, xLastHit: Double, yLastHit: Double) -> Bool {
        xHits > 0 && yHits > 0 && abs(xLastHit - yLastHit) <= Bounce.cornerSlop
    }

    /// The mark's hue after `hits` edges: the brand's own at the start and on a corner, else the next in the cycle.
    private static func hue(hits: Int, corner: Bool) -> Double {
        hits == 0 || corner ? 0 : Bounce.hues[(hits - 1) % 5 + 1]
    }

    /// The place along one axis after moving `distance` from `start`, the edges hit so far, and the distance at the last one.
    private static func axis(_ distance: Double, from start: Double, room: Double) -> (Double, Int, Double) {
        let travel = start + distance
        let lap = Int(travel / room)
        let along = travel - Double(lap) * room
        return (lap % 2 == 0 ? along : room - along, lap, Double(lap) * room - start)
    }
}

/// The crown-on-sofa mark, from the Compose app's `banner_logo` vector (a 120 by 120 viewport).
struct SofaMark: View {
    /// The sofa's own color (its body and arms), which the screen behind the mark wears in the mark's last hue.
    static let lavender = color(0xC9B8FF)
    /// The mark's dark ink, for words over a sofa color.
    static let ink = color(0x14111F)

    var body: some View {
        Canvas { context, size in
            let scale = min(size.width, size.height) / 120
            context.scaleBy(x: scale, y: scale)
            for (path, color) in SofaMark.parts { context.fill(path, with: .color(color)) }
        }
    }

    private static func color(_ hex: UInt32) -> Color {
        Color(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    private static func rounded(_ points: [(Double, Double)], quads: [Int: (Double, Double)] = [:]) -> Path {
        Path { path in
            for (index, point) in points.enumerated() {
                let to = CGPoint(x: point.0, y: point.1)
                if index == 0 {
                    path.move(to: to)
                } else if let control = quads[index] {
                    path.addQuadCurve(to: to, control: CGPoint(x: control.0, y: control.1))
                } else {
                    path.addLine(to: to)
                }
            }
            path.closeSubpath()
        }
    }

    private static let parts: [(Path, Color)] = [
        // Sofa backrest
        (rounded([(22, 46), (28, 40), (92, 40), (98, 46), (98, 70), (22, 70)], quads: [1: (22, 40), 3: (98, 40)]), color(0xB3A1EA)),
        // Sofa body and arms
        (rounded([(10, 62), (14, 58), (106, 58), (110, 62), (110, 92), (10, 92)], quads: [1: (10, 58), 3: (110, 58)]), color(0xC9B8FF)),
        (rounded([(6, 58), (12, 52), (20, 52), (20, 96), (6, 96)], quads: [1: (6, 52)]), color(0xC9B8FF)),
        (rounded([(100, 52), (108, 52), (114, 58), (114, 96), (100, 96)], quads: [2: (114, 52)]), color(0xC9B8FF)),
        // Cushion seam
        (rounded([(20, 72), (100, 72), (100, 75), (20, 75)]), color(0x9C8AD4)),
        // Legs
        (rounded([(14, 96), (14, 104), (20, 104), (20, 96)]), color(0x9C8AD4)),
        (rounded([(100, 96), (100, 104), (106, 104), (106, 96)]), color(0x9C8AD4)),
        // Crown
        (rounded([(36, 46), (36, 30), (48, 38), (60, 20), (72, 38), (84, 30), (84, 46)]), color(0xFFB547)),
        (rounded([(36, 40), (84, 40), (84, 46), (36, 46)]), color(0xE0902A)),
        (rounded([(60, 42), (62, 44), (60, 46), (58, 44)]), color(0x14111F)),
    ]
}

import SwiftUI

/// The break screen, as the Compose app draws it: black, with the crown-on-sofa mark drifting around and bouncing off
/// the edges the way an old DVD player's screensaver did, a new color on every edge and the brand colors back on a
/// corner. `remainingMs`, when known, counts down under the mark. It draws only; the player decides about the sound.
struct BreakScreen: View {
    let remainingMs: Int64?

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.animation) { context in
                let markSize = min(max(min(geometry.size.width, geometry.size.height) * 0.22, 96), 240)
                let unit = CGSize(width: markSize, height: markSize + 48)
                let bounce = Bounce(
                    // Counted from a fixed date, so the mark carries on where it was whenever the countdown redraws the screen.
                    seconds: context.date.timeIntervalSinceReferenceDate,
                    room: CGSize(width: max(geometry.size.width - unit.width, 1), height: max(geometry.size.height - unit.height, 1))
                )
                VStack(spacing: 8) {
                    SofaMark()
                        .frame(width: markSize, height: markSize)
                        .hueRotation(.degrees(bounce.hue))
                    Text(label)
                        .font(.headline)
                        .foregroundStyle(Color(red: 0.77, green: 0.73, blue: 0.85))
                }
                .frame(width: unit.width, height: unit.height)
                .position(x: bounce.x + unit.width / 2, y: bounce.y + unit.height / 2)
            }
        }
        .background(.black)
        .ignoresSafeArea()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }

    private var label: String {
        guard let remainingMs else { return "Commercial break" }
        let seconds = Int((remainingMs + 999) / 1000)
        return "Back in \(seconds / 60):\(String(format: "%02d", seconds % 60))"
    }
}

/// Where the mark is after `seconds`, worked out from the start rather than step by step: each axis runs back and forth
/// across its room at the same speed. Every edge it has hit moves the color on; a corner, both edges at once, brings
/// the brand colors back.
struct Bounce {
    static let speed: Double = 72
    static let cornerSlop: Double = 6
    static let hues: [Double] = [0, 60, 120, 180, 240, 300]

    let x: Double
    let y: Double
    let hue: Double

    init(seconds: Double, room: CGSize) {
        let distance = Bounce.speed * seconds
        let (x, xHits, xLastHit) = Bounce.axis(distance, from: room.width * 0.3, room: room.width)
        let (y, yHits, yLastHit) = Bounce.axis(distance, from: room.height * 0.2, room: room.height)
        self.x = x
        self.y = y
        let hits = xHits + yHits
        let corner = xHits > 0 && yHits > 0 && abs(xLastHit - yLastHit) <= Bounce.cornerSlop
        hue = hits == 0 || corner ? 0 : Bounce.hues[(hits - 1) % 5 + 1]
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

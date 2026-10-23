import XCTest

/// The iOS app driven by touch in a simulator. `testPlayChannel` runs against the fixture server
/// (`app/scripts/fixture_server.py`), whose address and token arrive as SOFAKING_SERVER and SOFAKING_TOKEN
/// (`TEST_RUNNER_SOFAKING_SERVER` and `TEST_RUNNER_SOFAKING_TOKEN` to `xcodebuild test`), and skips without them.
/// `testBreakScreen` watches a real channel's ad breaks on the server the app is already paired with; it runs only when
/// SOFAKING_BREAK_CHANNEL names the channel. Every launch mutes the player.
final class TouchTests: XCTestCase {
    /// The app under test, made fresh by each test's launch.
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
    }

    /// Plays Sofa Sports from the guide, which opens a playback session, and leaves the app, which releases it.
    @MainActor
    func testPlayChannel() throws {
        let server = try FixtureServer.fromEnvironment(since: Date())
        launch(["-serverUrl", server.url.absoluteString, "-apiToken", server.token])
        // News Now is a ten-second HLS clip that ends; Sofa Sports is live.
        play("Sofa Sports")
        eventually("Sofa Sports to open a playback session", timeout: 30) { !server.sessions().isEmpty }
        XCUIDevice.shared.press(.home)
        eventually("the app to release its session in the background") { server.sessions().isEmpty }
    }

    /// Random taps, swipes, back swipes from the screen's left edge and long presses against the fixture server, seeded
    /// so a walk replays from SOFAKING_WALK_SEED (and SOFAKING_WALK_STEPS, 150 by default). The app must keep running,
    /// and release its session once it is behind the home screen.
    @MainActor
    func testWalkWithRandomTouches() throws {
        let server = try FixtureServer.fromEnvironment(since: Date())
        launch(["-serverUrl", server.url.absoluteString, "-apiToken", server.token])
        let environment = ProcessInfo.processInfo.environment
        let seed = environment["SOFAKING_WALK_SEED"].flatMap(UInt64.init) ?? UInt64(Date().timeIntervalSince1970)
        let steps = environment["SOFAKING_WALK_STEPS"].flatMap(Int.init) ?? 150
        var random = SplitMix64(seed: seed)
        var walked: [String] = []
        var returns = 0
        for step in 0..<steps {
            let touch = Self.touches.pick(&random)
            walked.append(touch.name)
            touch.act(app, &random)
            switch app.state {
            case .runningForeground:
                break
            case .notRunning:
                XCTFail("The app stopped at touch \(step + 1) of seed \(seed) after \(walked.suffix(12).joined(separator: " "))")
                return
            default:
                // A link or a share sheet can leave the app; bring it back.
                returns += 1
                app.activate()
            }
        }
        print("Walk: seed \(seed), \(steps) touches, back to the app \(returns) times")
        let summary = XCTAttachment(string: "Seed \(seed), \(steps) touches: \(walked.joined(separator: " "))")
        summary.name = "Walk"
        summary.lifetime = .keepAlways
        add(summary)
        XCUIDevice.shared.press(.home)
        eventually("the app to release its session in the background, seed \(seed)", timeout: 30) { server.sessions().isEmpty }
    }

    /// The walk's touches, weighted.
    private static let touches = WeightedTouches([
        ("tap", 10, { app, random in app.coordinate(withNormalizedOffset: random.point()).tap() }),
        ("tapButton", 6, { app, random in
            // Picked from one snapshot and tapped by coordinate: the screen changes under the walk, and tapping an
            // element that has gone or moved fails the test ("No matches found", "Failed to not hittable").
            guard let screen = try? app.snapshot() else { return }
            let frames = TouchTests.buttonFrames(in: screen)
                .filter { screen.frame.contains(CGPoint(x: $0.midX, y: $0.midY)) }
            if frames.isEmpty { return }
            let frame = frames[Int(random.next() % UInt64(frames.count))]
            app.coordinate(withNormalizedOffset: .zero)
                .withOffset(CGVector(dx: frame.midX - screen.frame.minX, dy: frame.midY - screen.frame.minY)).tap()
        }),
        ("swipeUp", 3, { app, _ in app.swipeUp() }),
        ("swipeDown", 3, { app, _ in app.swipeDown() }),
        ("swipeLeft", 2, { app, _ in app.swipeLeft() }),
        ("swipeRight", 2, { app, _ in app.swipeRight() }),
        ("back", 3, { app, _ in
            // The system's back gesture: a swipe in from the left edge.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.005, dy: 0.5))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)))
        }),
        ("longPress", 1, { app, random in app.coordinate(withNormalizedOffset: random.point()).press(forDuration: 1.2) }),
    ])

    /// The frames of the enabled buttons in `snapshot` and below it.
    @MainActor
    private static func buttonFrames(in snapshot: any XCUIElementSnapshot) -> [CGRect] {
        let own = snapshot.elementType == .button && snapshot.isEnabled && !snapshot.frame.isEmpty ? [snapshot.frame] : []
        return own + snapshot.children.flatMap { buttonFrames(in: $0) }
    }

    /// Plays SOFAKING_BREAK_CHANNEL full screen for SOFAKING_BREAK_MINUTES (10 by default) and logs each time the break
    /// screen comes and goes, with a screenshot at each edge, so its timing can be read against the stream. Fails if
    /// the app stops.
    @MainActor
    func testBreakScreen() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let channel = environment["SOFAKING_BREAK_CHANNEL"], !channel.isEmpty else {
            throw XCTSkip("Watches a real channel's breaks: pass SOFAKING_BREAK_CHANNEL to a paired app")
        }
        let minutes = environment["SOFAKING_BREAK_MINUTES"].flatMap(Double.init) ?? 10
        launch([])
        play(channel)
        let breakScreen = labeled("Commercial break")
        let start = Date()
        var showing = false
        var edges: [String] = []
        while Date().timeIntervalSince(start) < minutes * 60 {
            XCTAssertEqual(app.state, .runningForeground, "The app stopped after \(edges.count) break edges\n\(edges.joined(separator: "\n"))")
            let now = breakScreen.exists
            if now != showing {
                showing = now
                let edge = String(format: "%7.1f s break screen %@", Date().timeIntervalSince(start), now ? "up" : "down")
                edges.append(edge)
                print("Break: \(edge)")
                let shot = XCTAttachment(screenshot: app.screenshot())
                shot.name = edge
                shot.lifetime = .keepAlways
                add(shot)
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        let summary = XCTAttachment(string: edges.isEmpty ? "No break in \(minutes) minutes" : edges.joined(separator: "\n"))
        summary.name = "Break edges"
        summary.lifetime = .keepAlways
        add(summary)
    }

    /// Launches the app muted with `arguments` (the debug host's launch arguments) and waits for the guide, or for the
    /// player when the app reopens the last channel it played.
    @MainActor
    private func launch(_ arguments: [String]) {
        app = XCUIApplication()
        app.launchArguments = arguments + ["-muted", "YES"]
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        eventually("the guide or the player to load", timeout: 60) { labeled("All ").exists || labeled("Now playing").exists }
    }

    /// Opens `channel` full screen from the guide with one tap on the program it airs now. The channel's name beside
    /// the row takes no taps, so the cell is found by its label, which starts with the channel and says "airing now".
    /// A player already on `channel` is left as it is; one on another channel goes back to the guide first.
    @MainActor
    private func play(_ channel: String) {
        let player = labeled("Now playing")
        if player.exists {
            // The player's label is "Now playing NUMBER  NAME", then ", TITLE" while a program is on.
            if player.label.hasSuffix("  " + channel) || player.label.contains("  " + channel + ", ") { return }
            app.tap()
            labeled("Back").tap()
            eventually("the guide to come back") { labeled("All ").exists }
        }
        let cell = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", channel + ", ", ", airing now")).firstMatch
        if !cell.waitForExistence(timeout: 30) {
            search(channel)
            // Other channels' programs can match too, and the landscape grid shows only a few rows between the header
            // and the tab bar, so it is dragged within that band; a swipe from the middle of the screen misses it.
            let low = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
            let high = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55))
            for _ in 0..<10 where !cell.waitForExistence(timeout: 2) {
                low.press(forDuration: 0.1, thenDragTo: high)
            }
        }
        XCTAssertTrue(cell.waitForExistence(timeout: 30), "No \(channel) on now in the guide\n\(screen)")
        cell.tap()
        eventually("\(channel) to open full screen") { !cell.exists }
    }

    /// Searches the guide for `text`, for a channel too far down to be on screen. Return is the field's Done, which
    /// puts the keyboard away; in landscape it leaves the results no room until then.
    @MainActor
    private func search(_ text: String) {
        let field = labeled("Search")
        guard field.waitForExistence(timeout: 5) else { return }
        field.tap()
        app.typeText(text + "\n")
    }

    /// The first element whose label contains `text`: Compose gives its semantics to accessibility as labels.
    @MainActor
    private func labeled(_ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    @MainActor
    private func eventually(_ what: String, timeout: TimeInterval = 20, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline {
                XCTFail("Timed out waiting for \(what)\n\(screen)")
                return
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
    }

    /// Each element with a label, indented by depth, with its type's raw value and its frame.
    @MainActor
    private var screen: String {
        guard let root = try? app.snapshot() else { return "(no snapshot)" }
        var lines: [String] = []
        func visit(_ element: XCUIElementSnapshot, _ depth: Int) {
            if !element.label.isEmpty {
                let frame = element.frame
                lines.append(String(repeating: " ", count: depth) + "\(element.elementType.rawValue) '\(element.label)' "
                    + "\(Int(frame.minX)),\(Int(frame.minY)) \(Int(frame.width))x\(Int(frame.height))")
            }
            element.children.forEach { visit($0, depth + 1) }
        }
        visit(root, 0)
        return lines.joined(separator: "\n")
    }
}

/// The fixture server's API, as the tests check what the app did.
struct FixtureServer {
    let url: URL
    let token: String
    /// Sessions that started before this are left out.
    let since: Date

    static func fromEnvironment(since: Date) throws -> FixtureServer {
        let environment = ProcessInfo.processInfo.environment
        guard let address = environment["SOFAKING_SERVER"], let url = URL(string: address), let token = environment["SOFAKING_TOKEN"] else {
            throw XCTSkip("Needs the fixture server: run app/scripts/fixture_server.py and pass SOFAKING_SERVER and SOFAKING_TOKEN")
        }
        return FixtureServer(url: url, token: token, since: since)
    }

    /// The app's open playback sessions since `since`; the server's own (recordings) are external.
    func sessions() -> [[String: Any]] {
        ((get("api/v1/admin/sessions") as? [[String: Any]]) ?? []).filter { session in
            guard (session["external"] as? Bool) != true else { return false }
            guard let started = (session["startedAt"] as? String).flatMap({ Self.seconds.date(from: String($0.prefix(19)) + "Z") }) else { return true }
            return started >= since.addingTimeInterval(-2)
        }
    }

    private static let seconds = ISO8601DateFormatter()

    private func get(_ path: String) -> Any? {
        var request = URLRequest(url: url.appendingPathComponent(path), timeoutInterval: 5)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let response = Response()
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, _, _ in
            response.data = data
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 6)
        return response.data.flatMap { try? JSONSerialization.jsonObject(with: $0) }
    }

    private final class Response: @unchecked Sendable {
        var data: Data?
    }
}

/// Touches to pick at random, each as often as its weight says.
struct WeightedTouches {
    typealias Act = @MainActor (XCUIApplication, inout SplitMix64) -> Void
    let touches: [(name: String, weight: Int, act: Act)]

    init(_ touches: [(name: String, weight: Int, act: Act)]) {
        self.touches = touches
    }

    func pick(_ random: inout SplitMix64) -> (name: String, act: Act) {
        var roll = Int(random.next() % UInt64(touches.map(\.weight).reduce(0, +)))
        for touch in touches {
            if roll < touch.weight { return (touch.name, touch.act) }
            roll -= touch.weight
        }
        return (touches[0].name, touches[0].act)
    }
}

/// A seeded generator, so a walk replays from its seed.
struct SplitMix64: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// A point inside the screen, clear of its outermost edges.
    mutating func point() -> CGVector {
        CGVector(dx: 0.03 + Double(next() % 940) / 1000, dy: 0.03 + Double(next() % 940) / 1000)
    }
}

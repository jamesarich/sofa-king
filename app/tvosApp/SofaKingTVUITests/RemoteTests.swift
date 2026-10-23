import XCTest

/// The Apple TV app driven with the Siri Remote in a simulator, against the fixture server
/// (`app/scripts/fixture_server.py`), whose address and token arrive as SOFAKING_SERVER and SOFAKING_TOKEN
/// (`TEST_RUNNER_SOFAKING_SERVER` and `TEST_RUNNER_SOFAKING_TOKEN` to `xcodebuild test`). Without them the tests skip.
/// The server's admin API is the check on what the remote did: a session opens, a recording is scheduled.
final class RemoteTests: XCTestCase {
    /// The app under test, made fresh by each test's launch.
    private var app: XCUIApplication!
    /// The fixture server, counting only the sessions this launch opened: a test that stopped with the player up
    /// leaves its session open until it expires.
    private var server: FixtureServer!

    @MainActor
    private var remote: XCUIRemote { .shared }

    /// Plays a channel from the list, records the program on now and switches channels from the transport bar's
    /// menu (#191), and leaves the player, which releases its session.
    @MainActor
    func testPlayerTransportMenu() throws {
        try launchToList()
        // News Now is the first row: below the search field on a first launch, above the last channel's row after one.
        let news = row("News Now")
        focus(news, by: Array(repeating: XCUIRemote.Button.down, count: 4) + Array(repeating: XCUIRemote.Button.up, count: 8))
        remote.press(.select)
        eventually("News Now to open a playback session", timeout: 30) { !server.sessions().isEmpty }
        let tuned = server.sessions().first?["channelId"] as? String

        let record = control("Record")
        reachInTransportBar(record)
        remote.press(.select)
        eventually("the server to schedule the recording") { server.recordings() > 0 }

        let channels = control("Channels")
        reachInTransportBar(channels)
        remote.press(.select)
        let all = menuItem("All channels")
        XCTAssertTrue(all.waitForExistence(timeout: 5), "Channels opened no All channels\n\(screen)")
        focus(all, pressing: .down)
        remote.press(.select)
        let sports = menuItem("3 Sofa Sports")
        XCTAssertTrue(sports.waitForExistence(timeout: 5), "All channels listed no Sofa Sports\n\(screen)")
        focus(sports, pressing: .down, presses: 16)
        remote.press(.select)
        eventually("the player to switch to Sofa Sports", timeout: 30) {
            server.sessions().contains { $0["channelId"] as? String != tuned }
        }

        leavePlayer()
    }

    /// Each tab takes focus from the tab bar, and Down moves into it without losing focus.
    @MainActor
    func testTabs() throws {
        try launchToList()
        let live = app.tabBars.buttons["Live"]
        focus(live, pressing: .up)
        for name in ["Recordings", "Settings", "Live"] {
            let tab = app.tabBars.buttons[name]
            XCTAssertTrue(tab.waitForExistence(timeout: 10), "No \(name) tab\n\(screen)")
            focus(tab, pressing: name == "Live" ? .left : .right, presses: 4)
            remote.press(.down)
            XCTAssertTrue(focused.exists, "Nothing has focus after Down on \(name)\n\(screen)")
            focus(tab, pressing: .up, presses: 6)
        }
    }

    /// Weighted random remote presses: fails if the app stops, and checks that it releases its playback session in
    /// the background. SOFAKING_WALK_SEED replays a walk; SOFAKING_WALK_KEYS sets its length.
    @MainActor
    func testWalkWithRandomKeys() throws {
        let environment = ProcessInfo.processInfo.environment
        let seed = environment["SOFAKING_WALK_SEED"].flatMap(UInt64.init) ?? UInt64(Date().timeIntervalSince1970)
        let count = environment["SOFAKING_WALK_KEYS"].flatMap(Int.init) ?? 200
        try launchToList()
        var random = SplitMix64(seed: seed)
        var pressed: [String] = []
        var relaunches = 0
        for index in 0..<count {
            let key = Self.keys.pick(&random)
            remote.press(key.button)
            pressed.append(key.name)
            switch app.state {
            case .runningForeground:
                continue
            case .runningBackground, .runningBackgroundSuspended:
                // Menu at the top leaves the app, as it should.
                relaunches += 1
                app.activate()
            default:
                XCTFail("The app stopped after key \(index + 1) of \(count), seed \(seed); the last keys: \(pressed.suffix(20).joined(separator: " "))")
            }
        }
        print("Walk: seed \(seed), \(count) keys, the app brought back \(relaunches) times")
        XCTAssertEqual(app.state, .runningForeground, "The app is not in front after the walk, seed \(seed)")

        // Behind the Home screen, the player releases its session.
        for _ in 0..<12 where app.state == .runningForeground {
            remote.press(.menu)
        }
        eventually("the app to release its sessions in the background, seed \(seed)") { server.sessions().isEmpty }
    }

    // MARK: - Driving

    /// The remote's buttons, weighted as the other fuzzers weigh them.
    private static let keys = WeightedKeys([
        (.up, "Up", 12), (.down, "Down", 14), (.left, "Left", 10), (.right, "Right", 12),
        (.select, "Select", 10), (.menu, "Menu", 6), (.playPause, "PlayPause", 2),
    ])

    /// Launches the app on the fixture server and waits for the channel list; a launch that opens the last channel
    /// full screen, as a cold start does, leaves the player first.
    @MainActor
    private func launchToList() throws {
        continueAfterFailure = false
        server = try FixtureServer.fromEnvironment(since: Date())
        app = XCUIApplication()
        app.launchArguments = ["-serverUrl", server.url.absoluteString, "-apiToken", server.token]
        app.launch()
        let news = row("News Now")
        eventually("the channel list or the last channel", timeout: 60) { news.exists || !server.sessions().isEmpty }
        // The launch decision opens the last channel once the lineup is in.
        RunLoop.current.run(until: Date().addingTimeInterval(3))
        if !server.sessions().isEmpty { leavePlayer() }
        XCTAssertTrue(news.waitForExistence(timeout: 10), "No channel list\n\(screen)")
    }

    /// Presses Menu until the player has gone and released its session; once more would leave the app.
    @MainActor
    private func leavePlayer() {
        for _ in 0..<3 {
            remote.press(.menu)
            let deadline = Date().addingTimeInterval(5)
            while Date() < deadline {
                if server.sessions().isEmpty { return }
                RunLoop.current.run(until: Date().addingTimeInterval(0.5))
            }
        }
        XCTFail("Menu did not leave the player and release its session\n\(screen)")
    }

    /// Shows the player's transport bar and moves focus to `element` in it: Down from the scrubber, then along the row.
    @MainActor
    private func reachInTransportBar(_ element: XCUIElement) {
        let moves: [XCUIRemote.Button] = [.down, .down] + Array(repeating: XCUIRemote.Button.right, count: 5) + Array(repeating: XCUIRemote.Button.left, count: 8)
        for move in moves {
            if !element.exists {
                // A click shows the bar; the system hides it again after a few idle seconds.
                remote.press(.select)
                _ = element.waitForExistence(timeout: 3)
            }
            if element.exists && element.hasFocus { return }
            print("[remote] looking for \(element.description), pressing \(move.rawValue) next:\n\(outline())")
            remote.press(move)
        }
        XCTAssertTrue(element.exists && element.hasFocus, "Could not reach \(element.description) in the transport bar\n\(screen)")
    }

    /// Presses `direction` until `element` has focus.
    @MainActor
    private func focus(_ element: XCUIElement, pressing direction: XCUIRemote.Button, presses: Int = 12) {
        focus(element, by: Array(repeating: direction, count: presses))
    }

    /// Makes `moves` in turn until `element` has focus.
    @MainActor
    private func focus(_ element: XCUIElement, by moves: [XCUIRemote.Button]) {
        for move in moves {
            if element.exists && element.hasFocus { return }
            remote.press(move)
        }
        XCTAssertTrue(element.exists && element.hasFocus, "Remote presses never focused \(element.description)\n\(screen)")
    }

    @MainActor
    private func eventually(_ what: String, timeout: TimeInterval = 20, _ check: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !check() {
            if Date() > deadline {
                XCTFail("Timed out waiting for \(what)\n\(screen)")
                return
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
    }

    // MARK: - Finding things

    /// A channel's row in the list, whose label starts with its number and name.
    @MainActor
    private func row(_ name: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "elementType IN %@ AND label CONTAINS %@", Self.pressable, name)).firstMatch
    }

    /// A button by its label, such as the transport bar's `Record`, which is a cell to accessibility.
    @MainActor
    private func control(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "elementType IN %@ AND label == %@", Self.pressable, label)).firstMatch
    }

    /// An item in the transport bar's menus, such as `All channels` or `3 Sofa Sports`.
    @MainActor
    private func menuItem(_ title: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "elementType IN %@ AND label BEGINSWITH %@", Self.pressable, title)).firstMatch
    }

    private static let pressable = [XCUIElement.ElementType.button, .cell, .menuItem].map { NSNumber(value: $0.rawValue) }

    @MainActor
    private var focused: XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
    }

    /// The screen as a failure message gives it: a screenshot kept in the result bundle, and the labelled and focused
    /// elements as an outline.
    @MainActor
    private var screen: String {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.lifetime = .keepAlways
        add(shot)
        return outline()
    }

    /// Each element with a label, other than text, or with focus (marked `*`), indented by depth, with its type's
    /// raw value and its frame.
    @MainActor
    private func outline() -> String {
        guard let root = try? app.snapshot() else { return "(no snapshot)" }
        var lines: [String] = []
        func visit(_ element: XCUIElementSnapshot, _ depth: Int) {
            if element.hasFocus || (!element.label.isEmpty && element.elementType != .staticText) {
                let frame = element.frame
                lines.append(String(repeating: " ", count: depth) + "\(element.hasFocus ? "*" : "")\(element.elementType.rawValue) '\(element.label)' "
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
            // `2026-10-23T12:39:32.517Z`: whole seconds are enough, a second either side.
            guard let started = (session["startedAt"] as? String).flatMap({ Self.seconds.date(from: String($0.prefix(19)) + "Z") }) else { return true }
            return started >= since.addingTimeInterval(-2)
        }
    }

    private static let seconds = ISO8601DateFormatter()

    /// How many recordings the DVR holds, scheduled or done.
    func recordings() -> Int {
        ((get("api/v1/recordings") as? [String: Any])?["recordings"] as? [Any])?.count ?? 0
    }

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

/// Remote buttons to pick at random, each as often as its weight says.
struct WeightedKeys {
    let keys: [(button: XCUIRemote.Button, name: String, weight: Int)]

    init(_ keys: [(button: XCUIRemote.Button, name: String, weight: Int)]) {
        self.keys = keys
    }

    func pick(_ random: inout SplitMix64) -> (button: XCUIRemote.Button, name: String) {
        var roll = Int(random.next() % UInt64(keys.map(\.weight).reduce(0, +)))
        for key in keys {
            if roll < key.weight { return (key.button, key.name) }
            roll -= key.weight
        }
        return (keys[0].button, keys[0].name)
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
}

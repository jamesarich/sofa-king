import Foundation
import Network
import SofaKingUi

/// Browses Bonjour for Sofa King servers for the app's Settings, reporting each one's IPv4 address and port on the
/// main queue. The same browse as the Apple TV app's `ServerBrowser`, handed to Kotlin as a `HostServerBrowser`.
final class BonjourBrowser: NSObject, HostServerBrowser {
    private var browser: NWBrowser?
    private var listener: ServerBrowserListener?
    private var resolving: [String: NWConnection] = [:]
    private var found: Set<String> = []

    func start(listener: ServerBrowserListener) {
        stop()
        self.listener = listener
        // Info.plist lists the type under NSBonjourServices; iOS refuses to browse for one it does not name.
        let browser = NWBrowser(for: .bonjour(type: "_sofaking._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in self?.update(results) }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
        listener = nil
        resolving.values.forEach { $0.cancel() }
        resolving = [:]
        found = []
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        let names = Set(results.compactMap { Self.name(of: $0.endpoint) })
        for (name, connection) in resolving where !names.contains(name) {
            connection.stateUpdateHandler = nil
            connection.cancel()
            resolving[name] = nil
        }
        for name in found.subtracting(names) {
            found.remove(name)
            listener?.lost(name: name)
        }
        for result in results {
            guard let name = Self.name(of: result.endpoint), resolving[name] == nil, !found.contains(name) else { continue }
            resolve(name: name, endpoint: result.endpoint)
        }
    }

    /// Bonjour names the service; a connection to it reports the IPv4 address and port the server answers on.
    private func resolve(name: String, endpoint: NWEndpoint) {
        let parameters = NWParameters.tcp
        if let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        let connection = NWConnection(to: endpoint, using: parameters)
        resolving[name] = connection
        connection.stateUpdateHandler = { [weak self] state in
            self?.connection(connection, named: name, changedTo: state)
        }
        connection.start(queue: .main)
    }

    private func connection(_ connection: NWConnection, named name: String, changedTo state: NWConnection.State) {
        switch state {
        case .ready:
            if case let .hostPort(host, port) = connection.currentPath?.remoteEndpoint, case let .ipv4(address) = host {
                // The address prints with its interface after a percent sign when it carries one.
                let ip = String(describing: address).split(separator: "%").first.map(String.init) ?? ""
                found.insert(name)
                listener?.resolved(name: name, host: ip, port: Int32(port.rawValue), version: nil)
            }
            finish(connection, named: name)
        case .waiting:
            // Bonjour found the server but its port does not answer yet, as while it starts; the connection would
            // wait for a network change, so it is dropped and the browse results are tried again shortly.
            finish(connection, named: name)
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.retryDelay) { [weak self] in
                guard let self, let browser = self.browser else { return }
                self.update(browser.browseResults)
            }
        case .failed, .cancelled:
            finish(connection, named: name)
        default:
            break
        }
    }

    private func finish(_ connection: NWConnection, named name: String) {
        connection.stateUpdateHandler = nil
        connection.cancel()
        if resolving[name] === connection { resolving[name] = nil }
    }

    private static let retryDelay: TimeInterval = 5

    private static func name(of endpoint: NWEndpoint) -> String? {
        if case let .service(name, _, _, _) = endpoint { return name }
        return nil
    }
}

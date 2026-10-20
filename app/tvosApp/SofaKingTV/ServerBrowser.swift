import Foundation
import Network
import Observation

/// The Sofa King servers advertising themselves on the network over Bonjour, listed while `start` runs.
@MainActor
@Observable
final class ServerBrowser {
    struct Server: Identifiable, Hashable {
        let name: String
        /// The address Settings stores, `http://IPV4:PORT`.
        let url: String
        var id: String { name }
    }

    private(set) var servers: [Server] = []
    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var resolving: [String: NWConnection] = [:]

    func start() {
        guard browser == nil else { return }
        // Info.plist lists the type under NSBonjourServices; tvOS refuses to browse for one it does not name.
        let browser = NWBrowser(for: .bonjour(type: "_sofaking._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated { self?.update(results) }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
        resolving.values.forEach { $0.cancel() }
        resolving = [:]
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        let names = Set(results.compactMap { Self.name(of: $0.endpoint) })
        servers.removeAll { !names.contains($0.name) }
        for result in results {
            guard let name = Self.name(of: result.endpoint), resolving[name] == nil,
                  !servers.contains(where: { $0.name == name }) else { continue }
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
            MainActor.assumeIsolated { self?.connection(connection, named: name, changedTo: state) }
        }
        connection.start(queue: .main)
    }

    private func connection(_ connection: NWConnection, named name: String, changedTo state: NWConnection.State) {
        switch state {
        case .ready:
            if case let .hostPort(host, port) = connection.currentPath?.remoteEndpoint, case let .ipv4(address) = host {
                servers.removeAll { $0.name == name }
                // The address prints with its interface after a percent sign when it carries one.
                let ip = String(describing: address).split(separator: "%").first.map(String.init) ?? ""
                servers.append(Server(name: name, url: "http://\(ip):\(port.rawValue)"))
                servers.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            }
            fallthrough
        case .failed, .cancelled:
            connection.stateUpdateHandler = nil
            connection.cancel()
            if resolving[name] === connection { resolving[name] = nil }
        default:
            break
        }
    }

    private static func name(of endpoint: NWEndpoint) -> String? {
        if case let .service(name, _, _, _) = endpoint { return name }
        return nil
    }
}

import Foundation
import Network

struct RobotError: LocalizedError {
    let code: String
    let message: String
    var errorDescription: String? { message }
}

/// TCP client for protocol 0.1. Each message is one JSON object and a newline.
final class RobotClient {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.jiadroid.tcp")
    private let lock = NSLock()
    private var buffer = Data()
    private var nextID = 1
    private var closed = false
    private var hello: [String: Any]?
    private let helloWait = DispatchSemaphore(value: 0)
    private var responses: [String: [String: Any]] = [:]
    private var waits: [String: DispatchSemaphore] = [:]

    private(set) var name = ""
    private(set) var servoCount = 0
    private var safetyValue = "ready"
    var safety: String {
        lock.lock()
        defer { lock.unlock() }
        return safetyValue
    }

    private init(_ connection: NWConnection) {
        self.connection = connection
    }

    static func connect(host: String, port: Int) throws -> RobotClient {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            throw plain("Can't reach \(host):\(port)")
        }
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 5
        let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: NWParameters(tls: nil, tcp: tcp))
        let client = RobotClient(connection)
        let ready = DispatchSemaphore(value: 0)
        let stateLock = NSLock()
        var failed = false
        var waiting = false
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                ready.signal()
            case .waiting:
                stateLock.lock()
                waiting = true
                stateLock.unlock()
            case .failed, .cancelled:
                stateLock.lock()
                failed = true
                stateLock.unlock()
                client.failAll()
                ready.signal()
            default:
                break
            }
        }
        connection.start(queue: client.queue)
        if ready.wait(timeout: .now() + 8) == .timedOut || connection.state != .ready {
            connection.cancel()
            stateLock.lock()
            let needsPermission = waiting && !failed
            stateLock.unlock()
            if needsPermission {
                throw plain("Allow local network access for Jiadroid, then connect again.")
            }
            throw plain("Can't reach \(host):\(port)")
        }
        client.receive()
        if client.helloWait.wait(timeout: .now() + 5) == .timedOut {
            client.close()
            throw plain("Simulator did not say hello")
        }
        client.lock.lock()
        let message = client.hello
        client.lock.unlock()
        try client.readHello(message)
        return client
    }

    func walk(_ decision: FollowDecision) throws {
        let body: [String: Any] = [
            "forward": Double(decision.forward),
            "lateral": Double(decision.lateral),
            "yaw": Double(decision.yaw),
            "neck_pitch": 0.0,
            "head_pitch": 0.0,
            "head_yaw": Double(decision.headYaw),
            "head_roll": 0.0,
        ]
        noteSafety(try request("walk.velocity", body: body))
    }

    func stop() throws {
        noteSafety(try request("robot.stop", body: [:]))
    }

    func close() {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        closed = true
        lock.unlock()
        let bye: [String: Any] = [
            "v": 1,
            "id": "bye",
            "kind": "req",
            "op": "session.bye",
            "body": [String: Any](),
        ]
        if let data = try? JSONSerialization.data(withJSONObject: bye) {
            var line = data
            line.append(0x0A)
            connection.send(content: line, completion: .contentProcessed { _ in })
        }
        connection.cancel()
        failAll()
    }

    private func readHello(_ message: [String: Any]?) throws {
        guard let message else { throw plain("Simulator did not say hello") }
        if text(message, "kind") != "evt" { throw plain("Simulator connection closed") }
        guard let body = message["body"] as? [String: Any] else { throw plain("Simulator hello was empty") }
        if text(body, "protocol") != "jiadroid" || text(body, "version") != "0.1" {
            throw plain("Not a Jiadroid simulator")
        }
        guard let robot = body["robot"] as? [String: Any] else { throw plain("Simulator hello was empty") }
        let announced = text(robot, "name")
        name = announced.isEmpty ? "Robot" : announced
        if let safetyBody = body["safety"] as? [String: Any], !text(safetyBody, "state").isEmpty {
            setSafety(text(safetyBody, "state"))
        }
        servoCount = countServos(body["devices"] as? [Any])
    }

    private func request(_ op: String, body: [String: Any]) throws -> [String: Any] {
        lock.lock()
        if closed {
            lock.unlock()
            throw plain("Simulator connection closed")
        }
        let id = String(nextID)
        nextID += 1
        let waiter = DispatchSemaphore(value: 0)
        waits[id] = waiter
        lock.unlock()

        let message: [String: Any] = ["v": 1, "id": id, "kind": "req", "op": op, "body": body]
        let data = try JSONSerialization.data(withJSONObject: message)
        var line = data
        line.append(0x0A)
        connection.send(content: line, completion: .contentProcessed { _ in })

        let timedOut = waiter.wait(timeout: .now() + 2) == .timedOut
        lock.lock()
        let result = responses.removeValue(forKey: id)
        waits.removeValue(forKey: id)
        lock.unlock()
        if timedOut || result == nil { throw plain("Timed out waiting for the simulator") }
        guard let result else { throw plain("Timed out waiting for the simulator") }
        if text(result, "kind") == "closed" || text(result, "kind") != "res" {
            throw plain("Simulator connection closed")
        }
        if let error = result["error"] as? [String: Any] {
            let reason = text(error, "message")
            throw RobotError(code: text(error, "code"), message: reason.isEmpty ? "The simulator refused the command" : reason)
        }
        return result["body"] as? [String: Any] ?? [:]
    }

    private func noteSafety(_ body: [String: Any]) {
        guard let safetyBody = body["safety"] as? [String: Any] else { return }
        let state = text(safetyBody, "state")
        if !state.isEmpty { setSafety(state) }
    }

    private func setSafety(_ state: String) {
        lock.lock()
        safetyValue = state
        lock.unlock()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                if !self.drain() { return }
            }
            if isComplete || error != nil || self.isClosed {
                self.failAll()
                return
            }
            self.receive()
        }
    }

    private var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    /// Returns false when the stream should stop.
    private func drain() -> Bool {
        while let newline = buffer.firstIndex(of: 0x0A) {
            let line = buffer.prefix(upTo: newline)
            buffer.removeSubrange(...newline)
            if line.count > 8192 {
                failAll()
                return false
            }
            var bytes = Data(line)
            if bytes.last == 0x0D { bytes.removeLast() }
            if bytes.isEmpty { continue }
            guard let message = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
                failAll()
                return false
            }
            let kind = text(message, "kind")
            if kind == "res" {
                let id = idText(message["id"])
                lock.lock()
                if waits[id] != nil {
                    responses[id] = message
                    waits[id]?.signal()
                }
                lock.unlock()
            } else if kind == "evt", text(message, "op") == "session.hello" {
                lock.lock()
                if hello == nil {
                    hello = message
                    lock.unlock()
                    helloWait.signal()
                } else {
                    lock.unlock()
                }
            }
        }
        if buffer.count > 8192 {
            failAll()
            return false
        }
        return true
    }

    private func failAll() {
        lock.lock()
        let closedMessage: [String: Any] = ["kind": "closed"]
        let announceHello = hello == nil
        if announceHello { hello = closedMessage }
        let pending = waits
        for id in pending.keys {
            responses[id] = closedMessage
        }
        lock.unlock()
        if announceHello { helloWait.signal() }
        pending.values.forEach { $0.signal() }
    }
}

private func countServos(_ devices: [Any]?) -> Int {
    guard let devices else { return 0 }
    return devices.reduce(0) { count, item in
        guard let device = item as? [String: Any], text(device, "type") == "servo" else { return count }
        return count + 1
    }
}

private func text(_ object: [String: Any], _ key: String) -> String {
    if let value = object[key] as? String { return value }
    if let value = object[key] as? NSNumber { return value.stringValue }
    return ""
}

private func idText(_ value: Any?) -> String {
    if let value = value as? String { return value }
    if let value = value as? NSNumber { return value.stringValue }
    return ""
}

private func plain(_ message: String) -> NSError {
    NSError(domain: "dev.jiadroid", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
}

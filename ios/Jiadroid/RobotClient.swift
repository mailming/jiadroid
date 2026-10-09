import Darwin
import Foundation
import Network

struct RobotError: LocalizedError {
    let code: String
    let message: String
    var errorDescription: String? { message }
}

/// Inclusive limit a robot announced for one command field.
struct Limit {
    let low: Double
    let high: Double
}

/// Stream client for protocol 0.2 (and 0.1 robots) over TCP (and future USB/BLE pipes).
/// Each message is one JSON object and a newline.
///
/// On connect the robot says what it is and which robot-level commands it accepts, with
/// limits. `walk` sends the Follow Me decision as `motion.velocity`, rescaled to those
/// limits, plus `head.pose` when the robot has a head. A 0.1 duck gets `walk.velocity`.
final class RobotClient {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.jiadroid.tcp")
    private let lock = NSLock()
    private var buffer = Data()
    private var nextID = 1
    private var closed = false
    private var hello: [String: Any]?
    private let helloWait = DispatchSemaphore(value: 0)
    private var firmwareDone: [String: Any]?
    private let firmwareWait = DispatchSemaphore(value: 0)
    private var responses: [String: [String: Any]] = [:]
    private var waits: [String: DispatchSemaphore] = [:]

    private static let supportedVersions: Set<String> = ["0.1", "0.2"]
    private static let helloTimeoutSeconds: TimeInterval = 15
    // Follow Me decides in Open Duck Mini units; `fit` rescales to the connected body.
    private static let duckForward = 0.15
    private static let duckLateral = 0.2
    private static let duckYaw = 1.0

    private(set) var name = ""
    private(set) var kind = "other"
    private(set) var servoCount = 0
    private(set) var deviceCount = 0
    /// op -> field -> limit, as announced in `session.hello`.
    private(set) var controls: [String: [String: Limit]] = [:]
    /// Mount ids from `session.hello`, in order.
    private(set) var mounts: [String] = []
    /// Sensor device ids from `session.hello`.
    private(set) var sensors: Set<String> = []
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
        if client.helloWait.wait(timeout: .now() + Self.helloTimeoutSeconds) == .timedOut {
            client.close()
            throw plain("Robot did not say hello over this link")
        }
        client.lock.lock()
        let message = client.hello
        client.lock.unlock()
        do {
            try client.readHello(message)
            client.declarePhone()
            return client
        } catch {
            client.close()
            throw error
        }
    }

    /// Tell the robot what this phone weighs and which dock it is on. A refusal does not drop the link.
    private func declarePhone() {
        guard !mounts.isEmpty else { return }
        let mount = mounts.contains("head") ? "head" : (mounts.contains("deck") ? "deck" : mounts[0])
        let phone = thisPhone()
        let size: [Double] = mount == "deck"
            ? [phone.height, phone.width, phone.thickness]
            : [phone.thickness, phone.width, phone.height]
        let body: [String: Any] = [
            "mount": mount,
            "mass": phone.mass,
            "size": size,
            "offset": [0.0, 0.0, 0.0],
            "name": phone.name,
        ]
        _ = try? request("payload.set", body: body)
    }

    func supports(_ op: String) -> Bool { controls[op] != nil }

    func setWifi(ssid: String, password: String) throws -> [String: Any] {
        try request("wifi.set", body: ["ssid": ssid, "password": password])
    }

    func clearWifi() throws -> [String: Any] {
        try request("wifi.clear", body: [:])
    }

    func wifiStatus() throws -> [String: Any] {
        try request("wifi.status", body: [:])
    }

    func hasSensor(_ id: String) -> Bool { sensors.contains(id) }

    /// Read a sensor announced in hello. Booleans come back as 0 / 1.
    func readSensor(_ id: String) throws -> Double {
        let body = try request("sensor.read", body: ["id": id])
        if let value = body["value"] as? Double { return value }
        if let value = body["value"] as? Int { return Double(value) }
        if let value = body["value"] as? NSNumber { return value.doubleValue }
        return 0
    }

    /// Stream a firmware.bin after `firmware.begin`. Prefer Wi‑Fi; USB is slower when available.
    func installFirmware(data: Data, onProgress: ((Int) -> Void)? = nil) throws {
        guard supports("firmware.begin") else {
            throw plain("This robot does not accept firmware updates")
        }
        guard data.count >= 1024 else {
            throw plain("Firmware file is too small")
        }
        lock.lock()
        firmwareDone = nil
        lock.unlock()
        while firmwareWait.wait(timeout: .now()) == .success {}
        _ = try request("firmware.begin", body: ["size": data.count], timeout: 8)
        let chunk = 4096
        var sent = 0
        while sent < data.count {
            if isClosed { throw plain("Robot connection closed during firmware upload") }
            let end = min(sent + chunk, data.count)
            let piece = data.subdata(in: sent..<end)
            let gate = DispatchSemaphore(value: 0)
            var sendFailed = false
            connection.send(content: piece, completion: .contentProcessed { error in
                sendFailed = error != nil
                gate.signal()
            })
            if gate.wait(timeout: .now() + 30) == .timedOut || sendFailed {
                throw plain("Robot connection closed during firmware upload")
            }
            sent = end
            onProgress?(sent)
        }
        if firmwareWait.wait(timeout: .now() + 180) == .timedOut {
            throw plain(
                isClosed
                    ? "Connection closed after upload — the robot may be rebooting. Reconnect in a few seconds."
                    : "Timed out waiting for firmware.complete"
            )
        }
        lock.lock()
        let done = firmwareDone
        lock.unlock()
        guard let done else {
            throw plain("Connection closed after upload — the robot may be rebooting. Reconnect in a few seconds.")
        }
        if text(done, "kind") == "closed" {
            throw plain("Connection closed after upload — the robot may be rebooting. Reconnect in a few seconds.")
        }
        let body = done["body"] as? [String: Any] ?? [:]
        if !(body["ok"] as? Bool ?? false) {
            let message = text(body, "message")
            throw plain(message.isEmpty ? "Firmware update failed" : message)
        }
    }

    func walk(_ decision: FollowDecision) throws {
        if let limits = controls["motion.velocity"] {
            let motion: [String: Any] = [
                "forward": fit(Double(decision.forward), Self.duckForward, limits["forward"]),
                "lateral": fit(Double(decision.lateral), Self.duckLateral, limits["lateral"]),
                "yaw": fit(Double(decision.yaw), Self.duckYaw, limits["yaw"]),
            ]
            noteSafety(try request("motion.velocity", body: motion))
            if let head = controls["head.pose"] {
                noteSafety(try request("head.pose", body: ["yaw": clamp(Double(decision.headYaw), head["yaw"])]))
            }
            return
        }
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
        guard let message else { throw plain("Robot did not say hello over this link") }
        if text(message, "kind") != "evt" { throw plain("Robot connection closed before hello") }
        guard let body = message["body"] as? [String: Any] else { throw plain("Robot hello was empty") }
        let version = text(body, "version")
        if text(body, "protocol") != "jiadroid" || !Self.supportedVersions.contains(version) {
            throw plain("Not a Jiadroid robot")
        }
        guard let robot = body["robot"] as? [String: Any] else { throw plain("Robot hello was empty") }
        let announced = text(robot, "name")
        name = announced.isEmpty ? "Robot" : announced
        let announcedKind = text(robot, "kind")
        kind = announcedKind.isEmpty ? (version == "0.1" ? "biped" : "other") : announcedKind
        if let safetyBody = body["safety"] as? [String: Any], !text(safetyBody, "state").isEmpty {
            setSafety(text(safetyBody, "state"))
        }
        let devices = body["devices"] as? [Any]
        servoCount = countServos(devices)
        deviceCount = devices?.count ?? 0
        sensors = sensorIds(devices)
        controls = version == "0.1" ? legacyDuckControls() : parseControls(body["controls"] as? [String: Any])
        mounts = parseMounts(body["mounts"] as? [Any])
    }

    private func request(_ op: String, body: [String: Any], timeout: TimeInterval = 2) throws -> [String: Any] {
        lock.lock()
        if closed {
            lock.unlock()
            throw plain("Robot connection closed")
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

        let timedOut = waiter.wait(timeout: .now() + timeout) == .timedOut
        lock.lock()
        let result = responses.removeValue(forKey: id)
        waits.removeValue(forKey: id)
        lock.unlock()
        if timedOut || result == nil { throw plain("Timed out waiting for the robot") }
        guard let result else { throw plain("Timed out waiting for the robot") }
        if text(result, "kind") == "closed" || text(result, "kind") != "res" {
            throw plain("Robot connection closed")
        }
        if let error = result["error"] as? [String: Any] {
            let reason = text(error, "message")
            throw RobotError(code: text(error, "code"), message: reason.isEmpty ? "The robot refused the command" : reason)
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
            // USB/TCP noise or a torn line must not kill the session.
            guard let message = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
                continue
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
            } else if kind == "evt" {
                let op = text(message, "op")
                if op == "session.hello" {
                    lock.lock()
                    if hello == nil {
                        hello = message
                        lock.unlock()
                        helloWait.signal()
                    } else {
                        lock.unlock()
                    }
                } else if op == "firmware.complete" {
                    lock.lock()
                    firmwareDone = message
                    lock.unlock()
                    firmwareWait.signal()
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
        let announceFirmware = firmwareDone == nil
        if announceFirmware { firmwareDone = closedMessage }
        let pending = waits
        for id in pending.keys {
            responses[id] = closedMessage
        }
        lock.unlock()
        if announceHello { helloWait.signal() }
        if announceFirmware { firmwareWait.signal() }
        pending.values.forEach { $0.signal() }
    }
}

private func sensorIds(_ devices: [Any]?) -> Set<String> {
    guard let devices else { return [] }
    var ids = Set<String>()
    for item in devices {
        guard let device = item as? [String: Any], text(device, "type") == "sensor" else { continue }
        let id = text(device, "id")
        if !id.isEmpty { ids.insert(id) }
    }
    return ids
}

private func countServos(_ devices: [Any]?) -> Int {
    guard let devices else { return 0 }
    return devices.reduce(0) { count, item in
        guard let device = item as? [String: Any], text(device, "type") == "servo" else { return count }
        return count + 1
    }
}

func parseControls(_ controls: [String: Any]?) -> [String: [String: Limit]] {
    guard let controls else { return [:] }
    var result: [String: [String: Limit]] = [:]
    for (op, value) in controls {
        guard let limits = value as? [String: Any] else { continue }
        var fields: [String: Limit] = [:]
        for (field, span) in limits {
            guard let pair = span as? [Any], pair.count == 2,
                  let low = (pair[0] as? NSNumber)?.doubleValue,
                  let high = (pair[1] as? NSNumber)?.doubleValue else { continue }
            fields[field] = Limit(low: low, high: high)
        }
        result[op] = fields
    }
    return result
}

func parseMounts(_ mounts: [Any]?) -> [String] {
    guard let mounts else { return [] }
    return mounts.compactMap { item in
        guard let object = item as? [String: Any] else { return nil }
        let id = text(object, "id")
        return id.isEmpty ? nil : id
    }
}

/// Bare-phone mass (kg) and width, height, thickness (m). Keep in step with docs/protocol.md.
struct PhoneSpec {
    let name: String
    let mass: Double
    let width: Double
    let height: Double
    let thickness: Double
}

private let phoneCatalog: [PhoneSpec] = [
    PhoneSpec(name: "iPhone 16 Pro Max", mass: 0.227, width: 0.0776, height: 0.1630, thickness: 0.0083),
    PhoneSpec(name: "iPhone 16 Plus", mass: 0.199, width: 0.0778, height: 0.1609, thickness: 0.0078),
    PhoneSpec(name: "iPhone 16 Pro", mass: 0.199, width: 0.0715, height: 0.1496, thickness: 0.0083),
    PhoneSpec(name: "iPhone 16", mass: 0.170, width: 0.0716, height: 0.1476, thickness: 0.0078),
    PhoneSpec(name: "iPhone 15 Pro Max", mass: 0.221, width: 0.0767, height: 0.1599, thickness: 0.0083),
    PhoneSpec(name: "iPhone 15 Plus", mass: 0.201, width: 0.0778, height: 0.1609, thickness: 0.0078),
    PhoneSpec(name: "iPhone 15 Pro", mass: 0.187, width: 0.0706, height: 0.1466, thickness: 0.0083),
    PhoneSpec(name: "iPhone 15", mass: 0.171, width: 0.0716, height: 0.1476, thickness: 0.0078),
    PhoneSpec(name: "Pixel 9 Pro XL", mass: 0.221, width: 0.0765, height: 0.1628, thickness: 0.0085),
    PhoneSpec(name: "Pixel 9 Pro", mass: 0.199, width: 0.0720, height: 0.1528, thickness: 0.0085),
    PhoneSpec(name: "Pixel 9", mass: 0.198, width: 0.0720, height: 0.1528, thickness: 0.0085),
    PhoneSpec(name: "Pixel 8 Pro", mass: 0.213, width: 0.0765, height: 0.1626, thickness: 0.0088),
    PhoneSpec(name: "Pixel 8", mass: 0.187, width: 0.0708, height: 0.1505, thickness: 0.0089),
]

private let genericPhone = PhoneSpec(name: "generic", mass: 0.190, width: 0.073, height: 0.155, thickness: 0.008)

/// utsname machine id -> marketing name. Unknown models use the generic phone.
private let iPhoneMachine = [
    "iPhone17,3": "iPhone 16",
    "iPhone17,4": "iPhone 16 Plus",
    "iPhone17,1": "iPhone 16 Pro",
    "iPhone17,2": "iPhone 16 Pro Max",
    "iPhone15,4": "iPhone 15",
    "iPhone15,5": "iPhone 15 Plus",
    "iPhone16,1": "iPhone 15 Pro",
    "iPhone16,2": "iPhone 15 Pro Max",
]

func thisPhone() -> PhoneSpec {
    var system = utsname()
    uname(&system)
    let machine = withUnsafeBytes(of: &system.machine) { raw -> String in
        let bytes = raw.bindMemory(to: CChar.self)
        guard let base = bytes.baseAddress else { return "" }
        return String(cString: base)
    }
    let marketing = iPhoneMachine[machine] ?? machine
    if let match = phoneCatalog.first(where: { marketing.localizedCaseInsensitiveContains($0.name) }) {
        return match
    }
    return PhoneSpec(name: machine.isEmpty ? "generic" : machine, mass: genericPhone.mass, width: genericPhone.width, height: genericPhone.height, thickness: genericPhone.thickness)
}

func legacyDuckControls() -> [String: [String: Limit]] {
    [
        "walk.velocity": [:],
        "motion.velocity": [
            "forward": Limit(low: -0.15, high: 0.15),
            "lateral": Limit(low: -0.2, high: 0.2),
            "yaw": Limit(low: -1.0, high: 1.0),
        ],
        "head.pose": [
            "neck_pitch": Limit(low: -0.34, high: 1.1),
            "pitch": Limit(low: -0.78, high: 0.3),
            "yaw": Limit(low: -0.5, high: 0.5),
            "roll": Limit(low: -0.5, high: 0.5),
        ],
    ]
}

/// Rescale a duck-unit value to another robot's limit. Unannounced fields become 0.
func fit(_ value: Double, _ duckBound: Double, _ limit: Limit?) -> Double {
    guard let limit else { return 0 }
    let bound = value >= 0 ? limit.high : -limit.low
    return value / duckBound * bound
}

func clamp(_ value: Double, _ limit: Limit?) -> Double {
    guard let limit else { return 0 }
    return min(max(value, limit.low), limit.high)
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

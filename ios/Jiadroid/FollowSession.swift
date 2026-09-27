import Combine
import CoreGraphics
import Foundation

/// On-phone duck, plus an optional link to the laptop simulator.
final class FollowSession: ObservableObject, CameraSink {
    let sim = SimModel()
    let status = StatusModel()
    let link = LinkModel()
    let camera = CameraSession()

    private let io = DispatchQueue(label: "dev.jiadroid.io")
    private let linkLock = NSLock()
    private let widthLock = NSLock()
    private var widthMeters: Float = markerWidthMM / 1000
    private var robot: RobotClient?
    private var timer: Timer?
    private var lastTick = Date()
    private var lastMarkerAt = Date.distantPast
    private var lastCommandKey: String?
    private var lastSendAt = Date.distantPast
    private var ticking = false
    private var connectGeneration = 0

    func start() {
        camera.sink = self
        camera.markerWidthMeters = { [weak self] in
            guard let self else { return markerWidthMM / 1000 }
            self.widthLock.lock()
            defer { self.widthLock.unlock() }
            return self.widthMeters
        }
        resume()
    }

    func resume() {
        camera.start()
        lastTick = Date()
        ticking = true
        timer?.invalidate()
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func pause() {
        ticking = false
        timer?.invalidate()
        timer = nil
        send(decide(Scene(visible: false, angle: 0, distance: 0)), force: true)
    }

    func stop() {
        pause()
        camera.stop()
        disconnect()
    }

    func toggleLink() {
        if robot != nil {
            disconnect()
        } else {
            connect()
        }
    }

    func cameraDidMeasure(_ measured: Scene, corners: [CGPoint], imageSize: CGSize, hfov: Double) {
        sim.scene = smoothScene(previous: sim.scene, measured: measured)
        lastMarkerAt = Date()
        sim.corners = corners
        sim.imageSize = imageSize
        sim.hfov = Float(hfov)
        link.cameraMessage = nil
    }

    func cameraFailed(_ message: String) {
        link.cameraMessage = message
    }

    private func tick() {
        guard ticking else { return }
        let meters = parsedMarkerWidthMeters()
        widthLock.lock()
        widthMeters = meters
        widthLock.unlock()
        let now = Date()
        let dt = Float(min(0.2, now.timeIntervalSince(lastTick)))
        lastTick = now
        let fresh = sim.scene.visible && now.timeIntervalSince(lastMarkerAt) < 0.4
        let shown = fresh ? sim.scene : Scene(visible: false, angle: 0, distance: 0)
        if !fresh {
            sim.scene = shown
            sim.corners = []
        }
        let decision = decide(shown)
        sim.gait = decision.command == "STOP" ? 0 : sim.gait + dt * 7
        stepPose(&sim.pose, forward: decision.forward, lateral: decision.lateral, yaw: decision.yaw, dt: dt)
        sim.trail.append((sim.pose.x, sim.pose.y))
        if sim.trail.count > 80 { sim.trail.removeFirst(sim.trail.count - 80) }
        show(decision, shown)
        send(decision, force: false)
    }

    private func show(_ decision: FollowDecision, _ scene: Scene) {
        status.situation = decision.situation
        status.command = decision.command
        status.moving = decision.command != "STOP"
        let range = scene.visible ? String(format: "%.2f m", scene.distance) : "no marker"
        let place = currentRobot()?.safety ?? "phone sim"
        status.detail = String(
            format: "forward %.2f m/s   turn %.2f rad/s   head %.2f   %@   %@",
            decision.forward,
            decision.yaw,
            decision.headYaw,
            range,
            place
        )
    }

    private func send(_ decision: FollowDecision, force: Bool) {
        guard let robot = currentRobot() else { return }
        let now = Date()
        let key = commandKey(decision)
        let changed = key != lastCommandKey
        let keepalive = decision.command != "STOP" && now.timeIntervalSince(lastSendAt) > 1
        if !force && !changed && !keepalive { return }
        lastCommandKey = key
        lastSendAt = now
        io.async { [weak self] in
            guard let self, self.currentRobot() === robot else { return }
            do {
                if decision.command == "STOP" {
                    try robot.stop()
                } else {
                    try robot.walk(decision)
                }
            } catch let error as RobotError {
                DispatchQueue.main.async { self.link.linkText = error.message }
            } catch {
                DispatchQueue.main.async { self.failLink(robot, error) }
            }
        }
    }

    private func commandKey(_ decision: FollowDecision) -> String {
        if decision.command == "STOP" { return "STOP" }
        return "\(decision.command):\(Int((decision.headYaw * 20).rounded()))"
    }

    private func connect() {
        if link.connecting || robot != nil { return }
        guard let endpoint = parseEndpoint(link.host) else {
            link.linkText = "Enter the laptop address, or use the phone simulator alone."
            return
        }
        link.connecting = true
        link.linkText = "Connecting…"
        connectGeneration += 1
        let generation = connectGeneration
        let (host, port) = endpoint
        io.async { [weak self] in
            do {
                let robot = try RobotClient.connect(host: host, port: port)
                DispatchQueue.main.async {
                    guard let self, self.connectGeneration == generation else {
                        robot.close()
                        return
                    }
                    self.link.connecting = false
                    self.setRobot(robot)
                    self.lastCommandKey = nil
                    self.link.connected = true
                    self.link.linkText = "Sending commands to \(robot.name) · \(robot.servoCount) servos at \(host):\(port)"
                }
            } catch {
                DispatchQueue.main.async {
                    guard let self, self.connectGeneration == generation else { return }
                    self.link.connecting = false
                    self.link.linkText = error.localizedDescription
                }
            }
        }
    }

    private func disconnect() {
        connectGeneration += 1
        let robot = swapRobot(nil)
        lastCommandKey = nil
        link.connected = false
        link.linkText = "Not connected. The duck above still follows the marker."
        if let robot { release(robot) }
    }

    private func failLink(_ robot: RobotClient, _ error: Error) {
        guard currentRobot() === robot else { return }
        setRobot(nil)
        lastCommandKey = nil
        link.connected = false
        link.linkText = error.localizedDescription
        release(robot)
    }

    private func release(_ robot: RobotClient) {
        io.async {
            try? robot.stop()
            robot.close()
        }
    }

    private func currentRobot() -> RobotClient? {
        linkLock.lock()
        defer { linkLock.unlock() }
        return robot
    }

    private func setRobot(_ robot: RobotClient?) {
        linkLock.lock()
        self.robot = robot
        linkLock.unlock()
    }

    private func swapRobot(_ robot: RobotClient?) -> RobotClient? {
        linkLock.lock()
        let previous = self.robot
        self.robot = robot
        linkLock.unlock()
        return previous
    }

    private func parseEndpoint(_ text: String) -> (String, Int)? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty { return nil }
        if let colon = value.lastIndex(of: ":"), colon > value.startIndex {
            let portText = value[value.index(after: colon)...]
            if !portText.isEmpty, portText.allSatisfy(\.isNumber), let port = Int(portText), (1...65535).contains(port) {
                let host = String(value[..<colon])
                if host.isEmpty { return nil }
                return (host, port)
            }
        }
        return (value, 8765)
    }

    private func parsedMarkerWidthMeters() -> Float {
        let mm = Float(link.markerMM) ?? markerWidthMM
        if mm < 10 || mm > 300 { return markerWidthMM / 1000 }
        return mm / 1000
    }
}

final class SimModel: ObservableObject {
    @Published var pose = Pose(x: 0, y: 0, heading: .pi / 2)
    @Published var scene = Scene(visible: false, angle: 0, distance: 0)
    @Published var gait: Float = 0
    @Published var hfov: Float = Float(70 * Double.pi / 180)
    @Published var trail: [(Float, Float)] = []
    @Published var corners: [CGPoint] = []
    @Published var imageSize = CGSize.zero
}

final class StatusModel: ObservableObject {
    @Published var situation = "Marker is lost"
    @Published var command = "STOP"
    @Published var moving = false
    @Published var detail = ""
}

final class LinkModel: ObservableObject {
    @Published var host = ""
    @Published var markerMM = "60"
    @Published var linkText = "Not connected. The duck above still follows the marker."
    @Published var connected = false
    @Published var connecting = false
    @Published var cameraMessage: String?
}

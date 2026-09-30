import Combine
import CoreGraphics
import Foundation

/// On-phone duck, plus an optional link to the laptop simulator.
final class FollowSession: ObservableObject, CameraSink {
    let sim = SimModel()
    let status = StatusModel()
    let link = LinkModel()
    let voice = VoiceModel()
    let camera = CameraSession()

    private let io = DispatchQueue(label: "dev.jiadroid.io")
    private let linkLock = NSLock()
    private let widthLock = NSLock()
    private var widthMeters: Float = markerWidthMM / 1000
    private var heightMeters: Float = personHeightMM / 1000
    private var subject = "Marker"
    private var lookX: Float = 0
    private var lookY: Float = 0
    private var robot: RobotClient?
    private var timer: Timer?
    private var lastTick = Date()
    private var lastMarkerAt = Date.distantPast
    private var lastCommandKey: String?
    private var lastSendAt = Date.distantPast
    private var ticking = false
    private var connectGeneration = 0
    private var voiceSession: VoiceSession?
    private var talk: [String] = []
    private var hearing: String?

    func start() {
        camera.sink = self
        camera.markerWidthMeters = { [weak self] in
            guard let self else { return markerWidthMM / 1000 }
            self.widthLock.lock()
            defer { self.widthLock.unlock() }
            return self.widthMeters
        }
        camera.personHeightMeters = { [weak self] in
            guard let self else { return personHeightMM / 1000 }
            self.widthLock.lock()
            defer { self.widthLock.unlock() }
            return self.heightMeters
        }
        if voiceSession == nil {
            voiceSession = VoiceSession(
                seeing: { [weak self] in self?.status.situation ?? "Marker is lost" },
                onLine: { [weak self] line in self?.voice.line = line },
                onTurn: { [weak self] who, text in self?.logTurn(who, text) },
                onHearing: { [weak self] text in self?.logHearing(text) }
            )
            loadBrain(save: false)
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
        voiceSession?.start()
    }

    func pause() {
        ticking = false
        timer?.invalidate()
        timer = nil
        voiceSession?.stop()
        send(decide(Scene(visible: false, angle: 0, distance: 0), subject: subject), force: true)
    }

    func stop() {
        pause()
        camera.stop()
        voiceSession?.destroy()
        voiceSession = nil
        disconnect()
    }

    func sayTyped() {
        let text = voice.say.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return }
        voice.say = ""
        voiceSession?.answer(text)
    }

    func useBrain(save: Bool) {
        loadBrain(save: save)
    }

    /// Showing the camera preview can reset the microphone. Open it again.
    func reopenMic() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            self?.voiceSession?.reopen()
        }
    }

    func toggleLink() {
        if robot != nil {
            disconnect()
        } else {
            connect()
        }
    }

    func cameraDidMeasure(_ sighting: Sighting) {
        if sighting.subject == "Person", subject == "Marker", sim.scene.visible, Date().timeIntervalSince(lastMarkerAt) < 0.4 {
            return
        }
        if sighting.subject != subject {
            subject = sighting.subject
            sim.scene = Scene(visible: false, angle: 0, distance: 0)
        }
        sim.scene = smoothScene(previous: sim.scene, measured: sighting.scene)
        lastMarkerAt = Date()
        sim.corners = sighting.corners
        sim.imageSize = sighting.imageSize
        sim.hfov = Float(sighting.hfov)
        sim.gazeY = sighting.gazeY
        sim.label = sighting.label
        link.cameraMessage = nil
    }

    func cameraFailed(_ message: String) {
        link.cameraMessage = message
    }

    private func tick() {
        guard ticking else { return }
        let meters = parsedMarkerWidthMeters()
        let height = parsedPersonHeightMeters()
        widthLock.lock()
        widthMeters = meters
        heightMeters = height
        widthLock.unlock()
        let now = Date()
        let dt = Float(min(0.2, now.timeIntervalSince(lastTick)))
        lastTick = now
        let fresh = sim.scene.visible && now.timeIntervalSince(lastMarkerAt) < 0.4
        let shown = fresh ? sim.scene : Scene(visible: false, angle: 0, distance: 0)
        if !fresh {
            sim.scene = shown
            sim.corners = []
            sim.gazeY = 0
        }
        let decision = decide(shown, subject: subject)
        sim.gait = decision.command == "STOP" ? 0 : sim.gait + dt * 7
        stepPose(&sim.pose, forward: decision.forward, lateral: decision.lateral, yaw: decision.yaw, dt: dt)
        sim.trail.append((sim.pose.x, sim.pose.y))
        if sim.trail.count > 80 { sim.trail.removeFirst(sim.trail.count - 80) }
        let targetX: Float
        switch decision.command {
        case "TURN LEFT": targetX = 1
        case "TURN RIGHT": targetX = -1
        default: targetX = -min(1, max(-1, decision.headYaw / 0.5))
        }
        let vertical: Float = shown.visible ? sim.gazeY : 0
        lookX += (targetX - lookX) * 0.35
        lookY += (vertical - lookY) * 0.25
        sim.lookX = lookX
        sim.lookY = lookY
        show(decision, shown)
        send(decision, force: false)
    }

    private func show(_ decision: FollowDecision, _ scene: Scene) {
        status.situation = decision.situation
        status.command = decision.command
        status.moving = decision.command != "STOP"
        let range = scene.visible ? String(format: "%.2f m", scene.distance) : "nothing seen"
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
                    self.link.linkText = "Sending commands to \(robot.name) (\(robot.kind)) · \(robot.deviceCount) devices at \(host):\(port)"
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
        link.linkText = "Not connected. The duck above still follows what the camera sees."
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

    private func parsedPersonHeightMeters() -> Float {
        let mm = Float(link.personMM) ?? personHeightMM
        if mm < 100 || mm > 2500 { return personHeightMM / 1000 }
        return mm / 1000
    }

    private func logTurn(_ who: String, _ text: String) {
        hearing = nil
        talk.append("\(who): \(text)")
        while talk.count > Self.talkLines { talk.removeFirst() }
        showTalk()
    }

    private func logHearing(_ text: String) {
        hearing = "Hearing: \(text)"
        voice.line = hearing ?? text
        showTalk()
    }

    private func showTalk() {
        var lines = talk
        if let hearing { lines.append(hearing) }
        voice.talkLog = lines.isEmpty
            ? "What you say and what it answers shows here."
            : lines.joined(separator: "\n")
        if let last = lines.last {
            voice.line = last
        }
    }

    private func loadBrain(save: Bool) {
        let defaults = UserDefaults.standard
        if save {
            defaults.set(voice.brainURL, forKey: Self.prefBrainURL)
            defaults.set(voice.brainModel, forKey: Self.prefBrainModel)
            defaults.set(voice.brainKey, forKey: Self.prefBrainKey)
        } else {
            voice.brainURL = defaults.string(forKey: Self.prefBrainURL) ?? ""
            voice.brainModel = defaults.string(forKey: Self.prefBrainModel) ?? ""
            voice.brainKey = defaults.string(forKey: Self.prefBrainKey) ?? ""
        }
        let url = voice.brainURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = voice.brainModel.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = voice.brainKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if url.isEmpty || model.isEmpty {
            voiceSession?.brain = nil
            voice.brainStatus = "Replies come from the phone. Add a model URL for real conversation."
        } else {
            voiceSession?.brain = Brain(baseURL: url, model: model, key: key)
            voice.brainStatus = "Replies come from \(model) at \(url)."
        }
    }

    private static let talkLines = 40
    private static let prefBrainURL = "brain_url"
    private static let prefBrainModel = "brain_model"
    private static let prefBrainKey = "brain_key"
}

final class SimModel: ObservableObject {
    @Published var pose = Pose(x: 0, y: 0, heading: .pi / 2)
    @Published var scene = Scene(visible: false, angle: 0, distance: 0)
    @Published var gait: Float = 0
    @Published var hfov: Float = Float(70 * Double.pi / 180)
    @Published var trail: [(Float, Float)] = []
    @Published var corners: [CGPoint] = []
    @Published var imageSize = CGSize.zero
    @Published var gazeY: Float = 0
    @Published var lookX: Float = 0
    @Published var lookY: Float = 0
    @Published var label = ""
}

final class StatusModel: ObservableObject {
    @Published var situation = "Marker is lost"
    @Published var command = "STOP"
    @Published var moving = false
    @Published var detail = ""
}

final class LinkModel: ObservableObject {
    @Published var host = ""
    @Published var markerMM = "120"
    @Published var personMM = "1700"
    @Published var linkText = "Not connected. The duck above still follows what the camera sees."
    @Published var connected = false
    @Published var connecting = false
    @Published var cameraMessage: String?
}

final class VoiceModel: ObservableObject {
    @Published var line = "Listening"
    @Published var talkLog = "What you say and what it answers shows here."
    @Published var say = ""
    @Published var brainURL = ""
    @Published var brainModel = ""
    @Published var brainKey = ""
    @Published var brainStatus = "Replies come from the phone. Add a model URL for real conversation."
}

import Foundation

/// Printed width of the mini-person code, in millimeters. The large sheet is 120.
let markerWidthMM: Float = 120

let markerPayload = "jiadroid:person"

/// Standing height of the person to follow, in millimeters. An adult is about 1700.
let personHeightMM: Float = 1700

/// Shoulder joint to shoulder joint, as a share of standing height.
private let shoulderShare: Float = 0.22

/// Shoulder midpoint to hip midpoint, as a share of standing height.
private let torsoShare: Float = 0.29

private let alignRad: Float = 0.28
private let closeM: Float = 0.40
private let slowM: Float = 0.75
private let farM: Float = 1.40

/// Follow decisions for the phone test.
///
/// The marker is a printed mini person, standing in for a real person.
/// `Scene.angle` is radians, positive when the marker is to the right.
/// `Scene.distance` is meters. Walk units match the Open Duck Mini:
/// forward is meters per second, yaw is radians per second, and head yaw is radians.
struct Scene: Equatable {
    var visible: Bool
    var angle: Float
    var distance: Float
}

struct FollowDecision: Equatable {
    var situation: String
    var command: String
    var forward: Float
    var lateral: Float
    var yaw: Float
    var headYaw: Float
}

struct Pose {
    var x: Float
    var y: Float
    var heading: Float
}

func measure(
    centerX: Float,
    widthPx: Float,
    imageWidth: Int,
    hfovRad: Double,
    markerWidthM: Float
) -> Scene {
    if imageWidth <= 0 || widthPx < 8 || markerWidthM <= 0 { return Scene(visible: false, angle: 0, distance: 0) }
    if hfovRad <= 0.2 || hfovRad >= 2.8 { return Scene(visible: false, angle: 0, distance: 0) }
    let fx = (Double(imageWidth) / 2.0) / tan(hfovRad / 2.0)
    if fx <= 1 { return Scene(visible: false, angle: 0, distance: 0) }
    let distance = Float(Double(markerWidthM) * fx / Double(widthPx))
    if distance < 0.05 || distance > 6 { return Scene(visible: false, angle: 0, distance: 0) }
    let angle = Float(atan(Double(centerX - Float(imageWidth) / 2) / fx))
    return Scene(visible: true, angle: angle, distance: distance)
}

struct LandmarkPoint {
    var x: Float
    var y: Float

    func distance(to other: LandmarkPoint) -> Float {
        hypot(other.x - x, other.y - y)
    }
}

/// Reads a person from body landmarks in upright image pixels.
///
/// Distance comes from shoulder width and from torso length, whichever says
/// closer. A person turned sideways has narrow shoulders but the same torso,
/// so they are never mistaken for someone far away.
func measurePerson(
    shoulders: (LandmarkPoint, LandmarkPoint),
    hips: (LandmarkPoint, LandmarkPoint)?,
    imageWidth: Int,
    hfovRad: Double,
    personHeightM: Float
) -> Scene {
    if imageWidth <= 0 || personHeightM <= 0 { return Scene(visible: false, angle: 0, distance: 0) }
    if hfovRad <= 0.2 || hfovRad >= 2.8 { return Scene(visible: false, angle: 0, distance: 0) }
    let fx = (Double(imageWidth) / 2.0) / tan(hfovRad / 2.0)
    let shoulderPx = shoulders.0.distance(to: shoulders.1)
    let top = LandmarkPoint(
        x: (shoulders.0.x + shoulders.1.x) / 2,
        y: (shoulders.0.y + shoulders.1.y) / 2
    )
    var distance = Float.greatestFiniteMagnitude
    if shoulderPx >= 8 {
        distance = Float(Double(personHeightM * shoulderShare) * fx / Double(shoulderPx))
    }
    if let hips {
        let bottom = LandmarkPoint(x: (hips.0.x + hips.1.x) / 2, y: (hips.0.y + hips.1.y) / 2)
        let torsoPx = top.distance(to: bottom)
        if torsoPx >= 8 {
            distance = min(distance, Float(Double(personHeightM * torsoShare) * fx / Double(torsoPx)))
        }
    }
    if distance < 0.05 || distance > 8 { return Scene(visible: false, angle: 0, distance: 0) }
    let angle = Float(atan(Double(top.x - Float(imageWidth) / 2) / fx))
    return Scene(visible: true, angle: angle, distance: distance)
}

func uprightFieldOfView(rotation: Int, uprightWidth: Int, uprightHeight: Int) -> Double {
    let longFov = 70.0 * Double.pi / 180.0
    if uprightWidth <= 0 || uprightHeight <= 0 { return longFov }
    let portrait = rotation == 90 || rotation == 270
    let shortOverLong = portrait
        ? Double(uprightWidth) / Double(uprightHeight)
        : Double(uprightHeight) / Double(uprightWidth)
    let clamped = min(1.0, max(0.3, shortOverLong))
    let shortFov = 2.0 * atan(tan(longFov / 2.0) * clamped)
    return portrait ? shortFov : longFov
}

func smoothScene(previous: Scene, measured: Scene) -> Scene {
    if !measured.visible || !previous.visible { return measured }
    return Scene(
        visible: true,
        angle: previous.angle * 0.5 + measured.angle * 0.5,
        distance: previous.distance * 0.6 + measured.distance * 0.4
    )
}

func decide(_ scene: Scene, subject: String = "Marker") -> FollowDecision {
    if !scene.visible { return stopped("\(subject) is lost") }
    if scene.distance < closeM { return stopped("\(subject) is too close") }
    let headYaw = min(0.5, max(-0.5, scene.angle))
    if scene.angle < -alignRad {
        return FollowDecision(situation: "\(subject) is left", command: "TURN LEFT", forward: 0.04, lateral: 0, yaw: 0.8, headYaw: headYaw)
    }
    if scene.angle > alignRad {
        return FollowDecision(situation: "\(subject) is right", command: "TURN RIGHT", forward: 0.04, lateral: 0, yaw: -0.8, headYaw: headYaw)
    }
    if scene.distance > farM {
        return FollowDecision(situation: "\(subject) is too far", command: "FORWARD", forward: 0.12, lateral: 0, yaw: 0, headYaw: headYaw)
    }
    if scene.distance < slowM {
        return FollowDecision(situation: "\(subject) is centered", command: "SLOW DOWN", forward: 0.05, lateral: 0, yaw: 0, headYaw: headYaw)
    }
    return FollowDecision(situation: "\(subject) is centered", command: "FORWARD", forward: 0.08, lateral: 0, yaw: 0, headYaw: headYaw)
}

/// When the target disappears, wait briefly then gently yaw left/right to reacquire
/// instead of sitting on STOP. Cancels immediately when the person is seen again.
final class LostSearch {
    private let grace: TimeInterval
    private let maxSearch: TimeInterval
    private let slice: TimeInterval
    private let searchYaw: Float
    private var lostSince: Date?
    private(set) var searching = false

    init(
        grace: TimeInterval = 0.4,
        maxSearch: TimeInterval = 8,
        slice: TimeInterval = 1.2,
        searchYaw: Float = 0.55
    ) {
        self.grace = grace
        self.maxSearch = maxSearch
        self.slice = slice
        self.searchYaw = searchYaw
    }

    func clear() {
        lostSince = nil
        searching = false
    }

    func enrich(scene: Scene, follow: FollowDecision, now: Date = Date(), subject: String) -> FollowDecision {
        if scene.visible {
            clear()
            return follow
        }
        if lostSince == nil { lostSince = now }
        let lostFor = now.timeIntervalSince(lostSince ?? now)
        if lostFor < grace || lostFor > maxSearch {
            searching = false
            return follow
        }
        searching = true
        let left = Int(lostFor / slice) % 2 == 0
        let yaw = left ? searchYaw : -searchYaw
        return FollowDecision(
            situation: "\(subject) is lost — looking",
            command: "SEARCH",
            forward: 0,
            lateral: 0,
            yaw: yaw,
            headYaw: min(0.5, max(-0.5, yaw))
        )
    }
}

func stepPose(_ pose: inout Pose, forward: Float, lateral: Float, yaw: Float, dt: Float) {
    let facingX = cos(pose.heading)
    let facingY = sin(pose.heading)
    let leftX = -sin(pose.heading)
    let leftY = cos(pose.heading)
    pose.x += (forward * facingX + lateral * leftX) * dt
    pose.y += (forward * facingY + lateral * leftY) * dt
    pose.heading = wrap(pose.heading + yaw * dt)
}

private func stopped(_ situation: String) -> FollowDecision {
    FollowDecision(situation: situation, command: "STOP", forward: 0, lateral: 0, yaw: 0, headYaw: 0)
}

private func wrap(_ angle: Float) -> Float {
    let pi = Float.pi
    var value = (angle + pi).truncatingRemainder(dividingBy: 2 * pi)
    if value < 0 { value += 2 * pi }
    return value - pi
}

enum FollowCheckError: Error, CustomStringConvertible {
    case failed(String)
    var description: String {
        switch self {
        case .failed(let message): return message
        }
    }
}

/// Same cases as the Android unit tests. `swiftc` runs these on the Mac; Xcode runs them too.
enum FollowLogicTests {
    static func run() throws {
        let hfov = 70.0 * Double.pi / 180.0

        let right = decide(Scene(visible: true, angle: 0.4, distance: 1.2))
        try expect(right.situation == "Marker is right", "right situation")
        try expect(right.command == "TURN RIGHT", "right command")
        try expect(right.yaw < 0, "right yaw")
        try expect(right.headYaw > 0, "right head")

        let left = decide(Scene(visible: true, angle: -0.4, distance: 1.2))
        try expect(left.command == "TURN LEFT", "left command")
        try expect(left.yaw > 0, "left yaw")
        try expect(left.headYaw < 0, "left head")

        let far = decide(Scene(visible: true, angle: 0, distance: 2))
        try expect(far.situation == "Marker is too far", "far situation")
        try expect(far.command == "FORWARD", "far command")
        try expect(abs(far.forward - 0.12) < 0.0001, "far speed")
        try expect(abs(far.yaw) < 0.0001, "far yaw")

        let near = decide(Scene(visible: true, angle: 0, distance: 0.5))
        try expect(near.command == "SLOW DOWN", "slow command")
        try expect(abs(near.forward - 0.05) < 0.0001, "slow speed")

        let close = decide(Scene(visible: true, angle: 0, distance: 0.2))
        try expect(close.situation == "Marker is too close", "close situation")
        try expect(close.command == "STOP", "close command")
        try expect(abs(close.forward) < 0.0001, "close speed")
        try expect(abs(close.headYaw) < 0.0001, "close head")

        let hidden = decide(Scene(visible: false, angle: 0.4, distance: 1))
        try expect(hidden.situation == "Marker is lost", "lost situation")
        try expect(hidden.command == "STOP", "lost command")

        let search = LostSearch(grace: 0.2, maxSearch: 5, slice: 1.0, searchYaw: 0.5)
        let t0 = Date()
        _ = search.enrich(
            scene: Scene(visible: false, angle: 0, distance: 0),
            follow: hidden,
            now: t0,
            subject: "Marker"
        )
        let looking = search.enrich(
            scene: Scene(visible: false, angle: 0, distance: 0),
            follow: hidden,
            now: t0.addingTimeInterval(1),
            subject: "Marker"
        )
        try expect(looking.command == "SEARCH", "search command")
        try expect(search.searching, "search active")
        _ = search.enrich(
            scene: Scene(visible: true, angle: 0, distance: 1),
            follow: decide(Scene(visible: true, angle: 0, distance: 1)),
            now: t0.addingTimeInterval(2),
            subject: "Marker"
        )
        try expect(!search.searching, "search clears on reacquire")

        let scene = measure(centerX: 800, widthPx: 100, imageWidth: 1000, hfovRad: hfov, markerWidthM: 0.06)
        try expect(scene.visible, "measured visible")
        try expect(scene.angle > 0.3, "measured angle")
        try expect(abs(scene.distance - 0.43) < 0.03, "measured distance \(scene.distance)")

        try expect(!measure(centerX: 500, widthPx: 4, imageWidth: 1000, hfovRad: hfov, markerWidthM: 0.06).visible, "tiny code")

        let personFov = uprightFieldOfView(rotation: 90, uprightWidth: 960, uprightHeight: 1280)
        let fx = 480.0 / tan(personFov / 2.0)
        let shoulderPx = Float(1.7 * 0.22 * fx / 3.0)
        let torsoPx = Float(1.7 * 0.29 * fx / 3.0)
        let shoulders = (
            LandmarkPoint(x: 480 - shoulderPx / 2, y: 400),
            LandmarkPoint(x: 480 + shoulderPx / 2, y: 400)
        )
        let hips = (
            LandmarkPoint(x: 470, y: 400 + torsoPx),
            LandmarkPoint(x: 490, y: 400 + torsoPx)
        )
        let adult = measurePerson(shoulders: shoulders, hips: hips, imageWidth: 960, hfovRad: personFov, personHeightM: 1.7)
        try expect(adult.visible, "adult visible")
        try expect(abs(adult.distance - 3) < 0.05, "adult distance \(adult.distance)")
        try expect(decide(adult, subject: "Person").situation == "Person is too far", "adult far")

        let sideTorso = Float(1.7 * 0.29 * fx / 0.3)
        let side = measurePerson(
            shoulders: (LandmarkPoint(x: 470, y: 100), LandmarkPoint(x: 490, y: 100)),
            hips: (LandmarkPoint(x: 470, y: 100 + sideTorso), LandmarkPoint(x: 490, y: 100 + sideTorso)),
            imageWidth: 960,
            hfovRad: personFov,
            personHeightM: 1.7
        )
        try expect(decide(side, subject: "Person").situation == "Person is too close", "sideways close")

        var pose = Pose(x: 0, y: 0, heading: Float.pi / 2)
        stepPose(&pose, forward: 0.1, lateral: 0, yaw: 0, dt: 1)
        try expect(abs(pose.x) < 0.0001, "pose x \(pose.x)")
        try expect(abs(pose.y - 0.1) < 0.0001, "pose y \(pose.y)")

        try expect(
            reply(heard: "hello there", seeing: "Person is centered").say
                == "Hello. I am Vicky. I can see you, and I can hear you.",
            "greeting"
        )
        try expect(!parseAttention(heard: "what do you see", name: "vicky").addressed, "wake required")
        let hey = parseAttention(heard: "hey Vicky, stop", name: "vicky")
        try expect(hey.addressed && hey.utterance == "stop", "wake strips name")
        try expect(reply(heard: "", seeing: "Person is centered").say == "Yes?", "name only")
        try expect(parseVoiceAction("turn right now")?.motion == .turnRight, "voice turn")
        try expect(parseVoiceAction("follow me")?.motion == .follow, "voice follow")
        try expect(parseVoiceAction("do not turn right") == nil, "voice negation")
        try expect(
            reply(heard: "what do you see", seeing: "Person is lost") == "I don't see anyone right now.",
            "lost sight"
        )
        try expect(
            reply(heard: "what are you looking at", seeing: "Person is left") == "I see someone. Person is left.",
            "seen person"
        )
    }

    private static func expect(_ condition: Bool, _ message: String) throws {
        if !condition { throw FollowCheckError.failed(message) }
    }
}

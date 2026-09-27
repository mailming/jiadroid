import Foundation

/// Printed width of the mini-person code, in millimeters.
let markerWidthMM: Float = 60

let markerPayload = "jiadroid:person"

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

func smoothScene(previous: Scene, measured: Scene) -> Scene {
    if !measured.visible || !previous.visible { return measured }
    return Scene(
        visible: true,
        angle: previous.angle * 0.5 + measured.angle * 0.5,
        distance: previous.distance * 0.6 + measured.distance * 0.4
    )
}

func decide(_ scene: Scene) -> FollowDecision {
    if !scene.visible { return stopped("Marker is lost") }
    if scene.distance < closeM { return stopped("Marker is too close") }
    let headYaw = min(0.5, max(-0.5, scene.angle))
    if scene.angle < -alignRad {
        return FollowDecision(situation: "Marker is left", command: "TURN LEFT", forward: 0.04, lateral: 0, yaw: 0.8, headYaw: headYaw)
    }
    if scene.angle > alignRad {
        return FollowDecision(situation: "Marker is right", command: "TURN RIGHT", forward: 0.04, lateral: 0, yaw: -0.8, headYaw: headYaw)
    }
    if scene.distance > farM {
        return FollowDecision(situation: "Marker is too far", command: "FORWARD", forward: 0.12, lateral: 0, yaw: 0, headYaw: headYaw)
    }
    if scene.distance < slowM {
        return FollowDecision(situation: "Marker is centered", command: "SLOW DOWN", forward: 0.05, lateral: 0, yaw: 0, headYaw: headYaw)
    }
    return FollowDecision(situation: "Marker is centered", command: "FORWARD", forward: 0.08, lateral: 0, yaw: 0, headYaw: headYaw)
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

        let scene = measure(centerX: 800, widthPx: 100, imageWidth: 1000, hfovRad: hfov, markerWidthM: 0.06)
        try expect(scene.visible, "measured visible")
        try expect(scene.angle > 0.3, "measured angle")
        try expect(abs(scene.distance - 0.43) < 0.03, "measured distance \(scene.distance)")

        try expect(!measure(centerX: 500, widthPx: 4, imageWidth: 1000, hfovRad: hfov, markerWidthM: 0.06).visible, "tiny code")

        var pose = Pose(x: 0, y: 0, heading: Float.pi / 2)
        stepPose(&pose, forward: 0.1, lateral: 0, yaw: 0, dt: 1)
        try expect(abs(pose.x) < 0.0001, "pose x \(pose.x)")
        try expect(abs(pose.y - 0.1) < 0.0001, "pose y \(pose.y)")
    }

    private static func expect(_ condition: Bool, _ message: String) throws {
        if !condition { throw FollowCheckError.failed(message) }
    }
}

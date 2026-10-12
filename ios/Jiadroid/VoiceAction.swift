import Foundation

/// Movement requests recognized locally from a completed speech transcript.
///
/// Motion is deliberately not inferred from the model's prose. Only a small,
/// explicit command vocabulary can move the robot, so a hallucinated reply
/// cannot become a motor command.
enum VoiceMotion {
    case stop
    case follow
    case forward
    case backward
    case turnLeft
    case turnRight
}

struct VoiceAction {
    let motion: VoiceMotion

    var description: String {
        switch motion {
        case .stop: return "stopping and holding"
        case .follow: return "resuming Follow Me"
        case .forward: return "moving forward briefly, then stopping"
        case .backward: return "moving backward briefly, then stopping"
        case .turnLeft: return "turning left briefly, then stopping"
        case .turnRight: return "turning right briefly, then stopping"
        }
    }

    /// Short spoken confirmation so the user knows the motors got the command.
    var spokenAck: String {
        switch motion {
        case .stop: return "Stopping."
        case .follow: return "Okay, following."
        case .forward: return "Going forward."
        case .backward: return "Backing up."
        case .turnLeft: return "Turning left."
        case .turnRight: return "Turning right."
        }
    }

    func decision() -> FollowDecision {
        switch motion {
        case .stop:
            return FollowDecision(situation: "Voice command: stopped", command: "STOP", forward: 0, lateral: 0, yaw: 0, headYaw: 0)
        case .follow:
            return FollowDecision(situation: "Voice command: follow", command: "STOP", forward: 0, lateral: 0, yaw: 0, headYaw: 0)
        case .forward:
            return FollowDecision(situation: "Voice command: forward", command: "FORWARD", forward: 0.04, lateral: 0, yaw: 0, headYaw: 0)
        case .backward:
            return FollowDecision(situation: "Voice command: backward", command: "BACKWARD", forward: -0.03, lateral: 0, yaw: 0, headYaw: 0)
        case .turnLeft:
            return FollowDecision(situation: "Voice command: turn left", command: "TURN LEFT", forward: 0.05, lateral: 0, yaw: 0.20, headYaw: -0.20)
        case .turnRight:
            return FollowDecision(situation: "Voice command: turn right", command: "TURN RIGHT", forward: 0.05, lateral: 0, yaw: -0.20, headYaw: 0.20)
        }
    }

    var durationMs: TimeInterval {
        switch motion {
        case .forward, .backward: return 1.4
        // Short nudge — keep the person in frame for Follow Me.
        case .turnLeft, .turnRight: return 0.22
        case .stop, .follow: return 0
        }
    }
}

func parseVoiceAction(_ transcript: String) -> VoiceAction? {
    let text = transcript.lowercased()
        .replacingOccurrences(of: #"[^\p{L}\p{N}']+"#, with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    if text.isEmpty { return nil }
    if text.range(of: #"\b(don't|dont|do not|never|not)\b"#, options: .regularExpression) != nil {
        return nil
    }
    if text.range(of: #"\b(stop|halt|freeze|emergency stop|hold still|stay)\b"#, options: .regularExpression) != nil {
        return VoiceAction(motion: .stop)
    }
    if text.range(of: #"\b(follow me|resume following|start following|come follow)\b"#, options: .regularExpression) != nil {
        return VoiceAction(motion: .follow)
    }
    if text == "left" ||
        text.range(
            of: #"\b((turn|rotate|spin|go|move|drive|walk|veer|head)(\s+(to(\s+the)?))?\s+left|left\s+turn|make a left)\b"#,
            options: .regularExpression
        ) != nil {
        return VoiceAction(motion: .turnLeft)
    }
    if text == "right" ||
        text.range(
            of: #"\b((turn|rotate|spin|go|move|drive|walk|veer|head)(\s+(to(\s+the)?))?\s+right|right\s+turn|make a right)\b"#,
            options: .regularExpression
        ) != nil {
        return VoiceAction(motion: .turnRight)
    }
    if text == "forward" ||
        text.range(
            of: #"\b((move|go|drive|walk)(\s+straight)?\s+forward|come forward)\b"#,
            options: .regularExpression
        ) != nil {
        return VoiceAction(motion: .forward)
    }
    if text == "back" || text == "backward" || text == "backwards" ||
        text.range(
            of: #"\b((move|go|drive|walk)(\s+straight)?\s+(back|backward|backwards)|come back|back up|backup)\b"#,
            options: .regularExpression
        ) != nil {
        return VoiceAction(motion: .backward)
    }
    return nil
}

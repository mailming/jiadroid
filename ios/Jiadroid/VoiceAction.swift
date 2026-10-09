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

    func decision() -> FollowDecision {
        switch motion {
        case .stop:
            return FollowDecision(situation: "Voice command: stopped", command: "STOP", forward: 0, lateral: 0, yaw: 0, headYaw: 0)
        case .follow:
            return FollowDecision(situation: "Voice command: follow", command: "STOP", forward: 0, lateral: 0, yaw: 0, headYaw: 0)
        case .forward:
            return FollowDecision(situation: "Voice command: forward", command: "FORWARD", forward: 0.08, lateral: 0, yaw: 0, headYaw: 0)
        case .backward:
            return FollowDecision(situation: "Voice command: backward", command: "BACKWARD", forward: -0.06, lateral: 0, yaw: 0, headYaw: 0)
        case .turnLeft:
            return FollowDecision(situation: "Voice command: turn left", command: "TURN LEFT", forward: 0, lateral: 0, yaw: 0.7, headYaw: -0.4)
        case .turnRight:
            return FollowDecision(situation: "Voice command: turn right", command: "TURN RIGHT", forward: 0, lateral: 0, yaw: -0.7, headYaw: 0.4)
        }
    }

    var durationMs: TimeInterval {
        switch motion {
        case .forward, .backward: return 1.2
        case .turnLeft, .turnRight: return 0.9
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
    if text.range(of: #"\b(stop|halt|freeze|emergency stop)\b"#, options: .regularExpression) != nil {
        return VoiceAction(motion: .stop)
    }
    if text.range(of: #"\b(follow me|resume following|start following)\b"#, options: .regularExpression) != nil {
        return VoiceAction(motion: .follow)
    }
    if text.range(of: #"\b(turn|rotate|spin)\s+(to\s+the\s+)?left\b"#, options: .regularExpression) != nil {
        return VoiceAction(motion: .turnLeft)
    }
    if text.range(of: #"\b(turn|rotate|spin)\s+(to\s+the\s+)?right\b"#, options: .regularExpression) != nil {
        return VoiceAction(motion: .turnRight)
    }
    if text.range(of: #"\b(move|go|drive|walk)\s+(straight\s+)?forward\b"#, options: .regularExpression) != nil {
        return VoiceAction(motion: .forward)
    }
    if text.range(of: #"\b(move|go|drive|walk)\s+(straight\s+)?(back|backward|backwards)\b"#, options: .regularExpression) != nil {
        return VoiceAction(motion: .backward)
    }
    return nil
}

import Foundation

/// Default wake name when the user has not assigned one.
let defaultRobotName = "vicky"

/// How long conversation stays open after the wake name (or each reply).
let attentionHoldSeconds: TimeInterval = 30

/// Whether a transcript is addressed to the robot, and the words after the wake name.
///
/// Background chat is ignored until someone says the name (e.g. "hey Vicky, stop").
/// After that, follow-ups without the name still count until `attentionHoldSeconds` of silence.
struct Attention {
    let addressed: Bool
    let utterance: String
}

func normalizeRobotName(_ name: String) -> String {
    let cleaned = name.trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    return cleaned.isEmpty ? defaultRobotName : cleaned
}

func parseAttention(heard: String, name: String) -> Attention {
    let said = heard.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    if said.isEmpty { return Attention(addressed: false, utterance: "") }
    let wake = NSRegularExpression.escapedPattern(for: normalizeRobotName(name))
    let lower = said.lowercased()
    guard lower.range(of: "\\b\(wake)\\b", options: .regularExpression) != nil else {
        return Attention(addressed: false, utterance: said)
    }
    var stripped = said
    stripped = stripped.replacingOccurrences(
        of: "(?i)^(?:hey|hi|hello|ok|okay|yo)\\s+\(wake)\\b[,:!]?\\s*",
        with: "",
        options: .regularExpression
    )
    stripped = stripped.replacingOccurrences(
        of: "(?i)\\b\(wake)\\b[,:!]?\\s*",
        with: "",
        options: .regularExpression
    )
    stripped = stripped.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    return Attention(addressed: true, utterance: stripped)
}

/// One wake opens a short conversation window.
final class AttentionSession {
    private let holdSeconds: TimeInterval
    private var engagedUntil: Date = .distantPast
    private let lock = NSLock()

    init(holdSeconds: TimeInterval = attentionHoldSeconds) {
        self.holdSeconds = holdSeconds
    }

    func clear() {
        lock.lock()
        engagedUntil = .distantPast
        lock.unlock()
    }

    func consider(heard: String, name: String, requireName: Bool, now: Date = Date()) -> Attention {
        let said = heard.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        if !requireName {
            touch(now)
            return Attention(addressed: true, utterance: said)
        }
        let parsed = parseAttention(heard: said, name: name)
        if parsed.addressed {
            touch(now)
            return parsed
        }
        lock.lock()
        let open = now < engagedUntil
        lock.unlock()
        if open {
            touch(now)
            return Attention(addressed: true, utterance: said)
        }
        return Attention(addressed: false, utterance: said)
    }

    private func touch(_ now: Date) {
        lock.lock()
        engagedUntil = now.addingTimeInterval(holdSeconds)
        lock.unlock()
    }
}

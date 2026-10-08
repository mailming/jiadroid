import Foundation

/// Default wake name when the user has not assigned one.
let defaultRobotName = "lulu"

/// Whether a transcript is addressed to the robot, and the words after the wake name.
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

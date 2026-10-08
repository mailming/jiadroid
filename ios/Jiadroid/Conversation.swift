import Foundation

/// Turns heard speech into a spoken reply.
///
/// Reachy Mini streams the microphone to a realtime language model. This phone
/// answers on the device so it can talk with no API key. The function is the
/// only place that reply comes from.
func reply(heard: String, seeing: String, name: String = defaultRobotName) -> String {
    let said = heard.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    let who = normalizeRobotName(name).capitalized
    if said.isEmpty { return "Yes?" }
    let lower = said.lowercased()
    if lower.range(of: #"\b(hi|hello|hey)\b"#, options: .regularExpression) != nil {
        return "Hello. I am \(who). I can see you, and I can hear you."
    }
    if lower.contains("who are you") || lower.contains("your name") {
        return "I am \(who), the phone on the robot. Say my name when you want me."
    }
    if lower.contains("follow") { return "I am following. Stay in front of me." }
    if lower.contains("see") || lower.contains("looking") || lower.contains("where") {
        if seeing.range(of: "lost", options: .caseInsensitive) != nil {
            return "I don't see anyone right now."
        }
        return "I see someone. \(seeing)."
    }
    return "I heard you say \(said)."
}

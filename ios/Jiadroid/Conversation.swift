import Foundation

/// Turns heard speech into a spoken reply plus a face emotion.
///
/// Reachy Mini streams the microphone to a realtime language model. This phone
/// answers on the device so it can talk with no API key. `memory` and `kb` let
/// local replies recall recent turns and persisted audience facts.
func reply(
    heard: String,
    seeing: String,
    name: String = defaultRobotName,
    memory: TalkMemory? = nil,
    kb: AudienceKb? = nil
) -> SpokenReply {
    let said = heard.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    let who = normalizeRobotName(name).capitalized
    if said.isEmpty { return SpokenReply(say: "Yes?", emotion: .curious) }
    if let recalled = kb?.recallReply(heard: said) { return recalled }
    if let recalled = memory?.recallReply(heard: said) { return recalled }
    let lower = said.lowercased()
    if lower.range(of: #"\b(hi|hello|hey)\b"#, options: .regularExpression) != nil {
        if (kb?.noteCount() ?? 0) > 0 || memory?.chatTurns().isEmpty == false {
            return SpokenReply(say: "Hello again. I'm still here.", emotion: .happy)
        }
        return SpokenReply(say: "Hello. I am \(who). I can see you, and I can hear you.", emotion: .happy)
    }
    if lower.contains("who are you") || lower.contains("your name") {
        return SpokenReply(
            say: "I am \(who), the phone on the robot. Say my name when you want me.",
            emotion: .happy
        )
    }
    if lower.contains("follow") {
        return SpokenReply(say: "I am following. Stay in front of me.", emotion: .excited)
    }
    if lower.contains("see") || lower.contains("looking") || lower.contains("where") {
        if seeing.range(of: "lost", options: .caseInsensitive) != nil {
            return SpokenReply(say: "I don't see anyone right now.", emotion: .confused)
        }
        return SpokenReply(say: "I see someone. \(seeing).", emotion: .curious)
    }
    if lower.range(of: #"\b(thank|thanks|good job|love you)\b"#, options: .regularExpression) != nil {
        return SpokenReply(say: "You're welcome.", emotion: .happy)
    }
    if lower.range(of: #"\b(sad|sorry|hurt|scared)\b"#, options: .regularExpression) != nil {
        return SpokenReply(say: "I'm here with you.", emotion: .sad)
    }
    if lower.range(of: #"(?:my name is|i am|i'm|call me)\s+\w+"#, options: .regularExpression) != nil {
        return SpokenReply(say: "Got it. I'll remember that on this phone.", emotion: .happy)
    }
    if lower.range(of: #"(?:remember(?: that)?|don't forget)\b"#, options: .regularExpression) != nil {
        return SpokenReply(say: "Okay, I'll keep that in my knowledge base.", emotion: .happy)
    }
    if lower.range(of: #"(?:i like|i love)\b"#, options: .regularExpression) != nil {
        return SpokenReply(say: "I'll remember you like that.", emotion: .happy)
    }
    return SpokenReply(say: "I heard you say \(said).", emotion: .neutral)
}

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
            return SpokenReply(say: "Hey, you're back! Want to play follow-the-leader again?", emotion: .happy)
        }
        return SpokenReply(
            say: "Hi! I'm \(who), the robot with phone eyes. What should we explore?",
            emotion: .happy
        )
    }
    if lower.contains("who are you") || lower.contains("your name") {
        return SpokenReply(
            say: "I'm \(who) — rolling buddy with big phone eyes. Say my name and I'll listen.",
            emotion: .happy
        )
    }
    if lower.contains("follow") {
        return SpokenReply(say: "On it! Stay where I can see you and I'll tag along.", emotion: .excited)
    }
    if lower.contains("see") || lower.contains("looking") || lower.contains("where") {
        if seeing.range(of: "lost", options: .caseInsensitive) != nil {
            return SpokenReply(say: "Hmm, my eyes lost you. Wave so I can find you!", emotion: .confused)
        }
        return SpokenReply(say: "I see you! \(seeing).", emotion: .curious)
    }
    if lower.range(of: #"\b(thank|thanks|good job|love you)\b"#, options: .regularExpression) != nil {
        return SpokenReply(say: "Aww, thanks! That made my wheels happy.", emotion: .happy)
    }
    if lower.range(of: #"\b(sad|sorry|hurt|scared)\b"#, options: .regularExpression) != nil {
        return SpokenReply(say: "I'm right here with you. Want to tell me about it?", emotion: .sad)
    }
    if lower.range(of: #"(?:my name is|i am|i'm|call me)\s+\w+"#, options: .regularExpression) != nil {
        return SpokenReply(say: "Got it! I'll remember that on this phone.", emotion: .happy)
    }
    if lower.range(of: #"(?:remember(?: that)?|don't forget)\b"#, options: .regularExpression) != nil {
        return SpokenReply(say: "Okay, locking that into my memory vault.", emotion: .happy)
    }
    if lower.range(of: #"(?:i like|i love)\b"#, options: .regularExpression) != nil {
        return SpokenReply(say: "Nice! I'll remember you like that.", emotion: .happy)
    }
    return SpokenReply(say: "I heard you say \(said). Tell me more!", emotion: .curious)
}

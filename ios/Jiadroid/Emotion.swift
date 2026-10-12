import Foundation

/// Face mood shared by conversation and the eyes.
enum Emotion: String, CaseIterable {
    case neutral
    case happy
    case curious
    case listening
    case thinking
    case confused
    case sad
    case excited

    static func parse(_ raw: String?) -> Emotion {
        let key = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return Emotion(rawValue: key) ?? .neutral
    }
}

struct SpokenReply {
    let say: String
    let emotion: Emotion
}

/// Pull a trailing `<<happy>>` tag (or JSON) off model text so TTS stays clean.
func parseSpokenReply(_ raw: String, fallback: Emotion = .neutral) -> SpokenReply {
    let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.isEmpty { return SpokenReply(say: "Yes?", emotion: fallback) }
    if text.hasPrefix("{"),
       let data = text.data(using: .utf8),
       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
        let say = ((json["say"] as? String) ?? (json["text"] as? String) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let emotion = Emotion.parse(json["emotion"] as? String)
        if !say.isEmpty { return SpokenReply(say: say, emotion: emotion) }
    }
    if let regex = try? NSRegularExpression(pattern: #"<<\s*([a-zA-Z_]+)\s*>>\s*$"#),
       let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
       let tagRange = Range(match.range(at: 1), in: text),
       let fullRange = Range(match.range, in: text) {
        let emotion = Emotion.parse(String(text[tagRange]))
        let say = text[..<fullRange.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
        return SpokenReply(say: say.isEmpty ? "Yes?" : String(say), emotion: emotion)
    }
    return SpokenReply(say: text, emotion: fallback)
}

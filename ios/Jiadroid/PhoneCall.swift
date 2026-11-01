import Contacts
import Foundation
import UIKit

/// Phone-call requests recognized locally from a completed speech transcript.
///
/// Like `VoiceAction`, this stays off the model: only an explicit “call …” phrase
/// can open the dialer, so a chat hallucination cannot ring someone.
struct CallRequest {
    let who: String

    var displayWho: String { canonicalContactLabel(who) }

    var spokenAck: String { "Calling \(displayWho)." }

    var missingNumberPrompt: String {
        "I don't have \(displayWho)'s number yet. " +
            "Say \(displayWho)'s number is …, or allow Contacts so I can look them up."
    }

    var notFoundPrompt: String {
        "I couldn't find \(displayWho). Tell me \(displayWho)'s number is …"
    }
}

func parseCallRequest(_ transcript: String) -> CallRequest? {
    let text = transcript.lowercased()
        .replacingOccurrences(of: #"[^\p{L}\p{N}'+]+"#, with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    if text.isEmpty { return nil }
    if text.range(of: #"\b(don't|dont|do not|never|not)\b"#, options: .regularExpression) != nil {
        return nil
    }
    if text.range(of: #"\bcall me\b"#, options: .regularExpression) != nil { return nil }
    if text.range(of: #"\b(what|who|how|why|when)\b"#, options: .regularExpression) != nil,
       text.range(of: #"\bcall\b"#, options: .regularExpression) != nil {
        return nil
    }

    for pattern in callPatterns {
        guard let match = firstRegexMatch(text, pattern: pattern), match.count >= 2,
              let cleaned = cleanCallWho(match[1])
        else { continue }
        return CallRequest(who: canonicalContactId(cleaned))
    }
    return nil
}

func canonicalContactKey(_ who: String) -> String { "contact-\(canonicalContactId(who))" }

func canonicalContactLabel(_ who: String) -> String {
    canonicalContactId(who)
        .split { $0.isWhitespace || $0 == "-" }
        .map { $0.capitalized }
        .joined(separator: " ")
}

func canonicalContactId(_ who: String) -> String {
    let cleaned = who.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    if cleaned.isEmpty { return cleaned }
    return contactAliases[cleaned] ?? cleaned
}

func normalizePhoneNumber(_ raw: String) -> String? {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return nil }
    let digits = trimmed.filter(\.isNumber)
    guard (7...16).contains(digits.count) else { return nil }
    return trimmed.hasPrefix("+") ? "+\(digits)" : digits
}

func parseContactNumberFact(_ transcript: String) -> (who: String, number: String)? {
    let lower = transcript.lowercased()
        .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !lower.isEmpty,
          let match = firstRegexMatch(lower, pattern: contactNumberFactPattern),
          match.count >= 3,
          let who = cleanCallWho(match[1]),
          let number = normalizePhoneNumber(match[2])
    else { return nil }
    return (who, number)
}

enum ContactDialer {
    static var hasContactsPermission: Bool {
        CNContactStore.authorizationStatus(for: .contacts) == .authorized
    }

    static func requestContactsAccess(completion: @escaping (Bool) -> Void) {
        CNContactStore().requestAccess(for: .contacts) { granted, _ in
            DispatchQueue.main.async { completion(granted) }
        }
    }

    static func findInContacts(who: String) -> String? {
        guard hasContactsPermission else { return nil }
        let store = CNContactStore()
        let keys: [CNKeyDescriptor] = [
            CNContactGivenNameKey as CNKeyDescriptor,
            CNContactFamilyNameKey as CNKeyDescriptor,
            CNContactOrganizationNameKey as CNKeyDescriptor,
            CNContactPhoneNumbersKey as CNKeyDescriptor,
        ]
        let candidates = contactNameCandidates(who)
        let request = CNContactFetchRequest(keysToFetch: keys)
        var exact: String?
        var contains: String?
        try? store.enumerateContacts(with: request) { contact, stop in
            let display = [contact.givenName, contact.familyName, contact.organizationName]
                .filter { !$0.isEmpty }
                .joined(separator: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !display.isEmpty, let phone = contact.phoneNumbers.first?.value.stringValue,
                  let normalized = normalizePhoneNumber(phone)
            else { return }
            let lower = display.lowercased()
            for name in candidates {
                let needle = name.lowercased()
                if lower == needle || lower.hasPrefix(needle) {
                    exact = normalized
                    stop.pointee = true
                    return
                }
                if contains == nil && lower.contains(needle) {
                    contains = normalized
                }
            }
        }
        return exact ?? contains
    }

    @discardableResult
    static func placeCall(number: String) -> Bool {
        guard let tel = normalizePhoneNumber(number),
              let url = URL(string: "tel://\(tel)"),
              UIApplication.shared.canOpenURL(url)
        else { return false }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
        return true
    }

    private static func contactNameCandidates(_ who: String) -> [String] {
        let id = canonicalContactId(who)
        let label = canonicalContactLabel(who)
        let aliases = contactNameVariants[id] ?? []
        var seen = Set<String>()
        return ([label, who.capitalized] + aliases).filter {
            let key = $0.lowercased()
            if $0.isEmpty || seen.contains(key) { return false }
            seen.insert(key)
            return true
        }
    }
}

private func cleanCallWho(_ who: String) -> String? {
    var w = who.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: #"^(the|a|an|to)\s+"#, with: "", options: .regularExpression)
        .replacingOccurrences(
            of: #"\s+(for me|now|please|real quick|quickly)$"#,
            with: "",
            options: .regularExpression
        )
        .trimmingCharacters(in: .whitespacesAndNewlines)
    if w.isEmpty || callWhoStop.contains(w) { return nil }
    if w.split(whereSeparator: \.isWhitespace).count > 4 { return nil }
    return w
}

private func firstRegexMatch(_ text: String, pattern: String) -> [String]? {
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
    let range = NSRange(text.startIndex..<text.endIndex, in: text)
    guard let match = regex.firstMatch(in: text, range: range) else { return nil }
    return (0..<match.numberOfRanges).compactMap { index in
        guard let swiftRange = Range(match.range(at: index), in: text) else { return nil }
        return String(text[swiftRange])
    }
}

private let callPatterns = [
    #"\b(?:please\s+)?(?:make|place)\s+(?:a\s+)?(?:phone\s+)?call\s+to\s+(?:my\s+)?(.+?)(?:\s+please)?$"#,
    #"\b(?:please\s+)?(?:can you\s+|could you\s+)?(?:call|phone|dial|ring)\s+(?:up\s+)?(?:my\s+)?(.+?)(?:\s+please)?$"#,
]

private let contactNumberFactPattern =
    #"(?:my\s+)?([a-z][a-z'’\s-]{0,40}?)(?:'s)?\s+(?:phone\s+)?(?:number|phone)\s+is\s+([+\d][\d\s().-]{6,24})"#

private let callWhoStop: Set<String> = [
    "me", "you", "it", "that", "this", "them", "him", "her", "us",
    "someone", "somebody", "anyone", "anybody", "everyone", "everybody",
    "back", "home", "out", "later",
]

private let contactAliases: [String: String] = [
    "mom": "mom", "mum": "mom", "mama": "mom", "mommy": "mom", "mother": "mom",
    "dad": "dad", "daddy": "dad", "papa": "dad", "father": "dad", "pa": "dad", "pop": "dad",
    "grandma": "grandma", "grandmother": "grandma", "nana": "grandma", "granny": "grandma",
    "gran": "grandma",
    "grandpa": "grandpa", "grandfather": "grandpa", "granddad": "grandpa", "grandad": "grandpa",
    "gramps": "grandpa",
    "sis": "sister", "sister": "sister", "bro": "brother", "brother": "brother",
    "wife": "wife", "husband": "husband", "girlfriend": "girlfriend", "boyfriend": "boyfriend",
    "partner": "partner", "aunt": "aunt", "uncle": "uncle", "cousin": "cousin",
]

private let contactNameVariants: [String: [String]] = [
    "mom": ["Mom", "Mum", "Mama", "Mommy", "Mother"],
    "dad": ["Dad", "Daddy", "Papa", "Father", "Pa", "Pop"],
    "grandma": ["Grandma", "Grandmother", "Nana", "Granny", "Gran"],
    "grandpa": ["Grandpa", "Grandfather", "Granddad", "Grandad", "Gramps"],
    "sister": ["Sister", "Sis"],
    "brother": ["Brother", "Bro"],
]

import XCTest
@testable import Jiadroid

final class FollowTests: XCTestCase {
    func testFollowLogicMatchesTheAndroidApp() throws {
        try FollowLogicTests.run()
    }

    func testTrailingEmotionTagIsStripped() {
        let spoken = parseSpokenReply("Hello there. <<happy>>")
        XCTAssertEqual(spoken.say, "Hello there.")
        XCTAssertEqual(spoken.emotion, .happy)
    }

    func testJsonEmotionReply() {
        let spoken = parseSpokenReply(#"{"say":"I see you.","emotion":"curious"}"#)
        XCTAssertEqual(spoken.say, "I see you.")
        XCTAssertEqual(spoken.emotion, .curious)
    }

    func testLocalGreetingIsHappy() {
        XCTAssertEqual(reply(heard: "hello", seeing: "Person is centered").emotion, .happy)
    }

    func testWakeOpensAConversationWindow() {
        let session = AttentionSession(holdSeconds: 30)
        let wake = session.consider(heard: "hey Lulu", name: "lulu", requireName: true, now: Date(timeIntervalSince1970: 1))
        XCTAssertTrue(wake.addressed)
        let followUp = session.consider(heard: "what do you see", name: "lulu", requireName: true, now: Date(timeIntervalSince1970: 5))
        XCTAssertTrue(followUp.addressed)
        XCTAssertEqual(followUp.utterance, "what do you see")
        let late = session.consider(heard: "are you there", name: "lulu", requireName: true, now: Date(timeIntervalSince1970: 40))
        XCTAssertFalse(late.addressed)
    }
}

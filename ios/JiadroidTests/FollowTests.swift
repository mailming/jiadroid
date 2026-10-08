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
}

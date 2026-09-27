import XCTest
@testable import Jiadroid

final class FollowTests: XCTestCase {
    func testFollowLogicMatchesTheAndroidApp() throws {
        try FollowLogicTests.run()
    }
}

import Foundation

@main
struct FollowCheck {
    static func main() {
        do {
            try FollowLogicTests.run()
            print("follow checks passed")
        } catch {
            fputs("\(error)\n", stderr)
            exit(1)
        }
    }
}

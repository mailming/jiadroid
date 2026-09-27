import SwiftUI

extension Color {
    static let paper = Color(hex: 0xF4F0E6)
    static let ink = Color(hex: 0x1C1915)
    static let muted = Color(hex: 0x6E675E)
    static let person = Color(hex: 0xC4512C)
    static let go = Color(hex: 0x1D6B45)
    static let stop = Color(hex: 0x8D3B2C)
    static let duck = Color(hex: 0xF0B429)
    static let beak = Color(hex: 0xE07A2F)
    static let cone = Color(hex: 0xE4D9C6)
    static let grid = Color(hex: 0xE7E0D4)
    static let trail = Color(hex: 0xB7AD9F)

    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

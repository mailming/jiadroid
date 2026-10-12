import Foundation

/// iOS counterpart to Android `UsbRobotLink`.
///
/// Apple does not expose raw USB CDC serial to iPhone apps (no DriverKit on
/// iPhone; External Accessory needs MFi). The ESP32-S3 DevKit speaks ordinary
/// CDC, so this phone cannot open that link the way Android OTG can.
///
/// Tap **USB** in the app to see this guidance; use Wi‑Fi **Connect** for iPhone.
enum UsbRobotLink {
    static func connect() throws -> RobotClient {
        throw plain(
            "iPhone cannot open USB serial to this ESP32. Power the board separately (or use an Android phone for USB‑C), join the same Wi‑Fi, enter the robot address, then tap Connect."
        )
    }
}

private func plain(_ message: String) -> NSError {
    NSError(domain: "dev.jiadroid", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
}

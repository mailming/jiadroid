import Foundation
import UIKit

/// True when the user is asking the robot to read a **back-camera** photo of paper
/// or a screen (math, coding, or other text) rather than report front-camera follow sighting.
func wantsCameraLook(_ heard: String) -> Bool {
    let lower = heard.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if lower.isEmpty { return false }
    // Keep follow-sight questions on the text "seeing" path.
    if lower.range(of: #"\bwhat (are you looking at|do you see)\b"#, options: .regularExpression) != nil {
        return false
    }
    let pattern =
        #"\b(?:look at (?:this|that)|(?:solve|read) (?:this|that)|help (?:me )?(?:with|solve) (?:this|that)|"# +
        #"what(?:'s| is) (?:this|that)(?: (?:problem|code|math|equation|puzzle))?|"# +
        #"what(?:'s| is) on (?:the )?(?:screen|paper|board|page)|"# +
        #"what does (?:this|that) say|can you (?:see|read) (?:this|that))\b"#
    return lower.range(of: pattern, options: .regularExpression) != nil
}

/// Downscale and recompress a JPEG for vision APIs.
func prepareVisionJpeg(_ jpeg: Data, maxSide: CGFloat = 1280, quality: CGFloat = 0.75) -> Data {
    guard let image = UIImage(data: jpeg) else { return jpeg }
    let longest = max(image.size.width, image.size.height)
    let scaled: UIImage
    if longest > maxSide {
        let factor = maxSide / longest
        let size = CGSize(width: max(1, image.size.width * factor), height: max(1, image.size.height * factor))
        let renderer = UIGraphicsImageRenderer(size: size)
        scaled = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    } else {
        scaled = image
    }
    return scaled.jpegData(compressionQuality: quality) ?? jpeg
}

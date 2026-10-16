package dev.jiadroid.follow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * True when the user is asking the robot to read a **back-camera** photo of paper
 * or a screen (math, coding, or other text) rather than report front-camera follow sighting.
 */
fun wantsCameraLook(heard: String): Boolean {
    val lower = heard.trim().lowercase()
    if (lower.isEmpty()) return false
    // Keep follow-sight questions on the text "seeing" path.
    if (FOLLOW_SIGHT.containsMatchIn(lower)) return false
    return LOOK_REQUEST.containsMatchIn(lower)
}

/** Downscale and recompress a JPEG for vision APIs. */
fun prepareVisionJpeg(jpeg: ByteArray, maxSide: Int = 1280, quality: Int = 75): ByteArray {
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
    val longest = max(bitmap.width, bitmap.height)
    val scaled = if (longest > maxSide) {
        val factor = maxSide.toFloat() / longest
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== bitmap) scaled.recycle()
    bitmap.recycle()
    return out.toByteArray()
}

private val FOLLOW_SIGHT = Regex("""\bwhat (are you looking at|do you see)\b""")

private val LOOK_REQUEST = Regex(
    """\b(?:look at (?:this|that)|(?:solve|read) (?:this|that)|help (?:me )?(?:with|solve) (?:this|that)|""" +
        """what(?:'s| is) (?:this|that)(?: (?:problem|code|math|equation|puzzle))?|""" +
        """what(?:'s| is) on (?:the )?(?:screen|paper|board|page)|""" +
        """what does (?:this|that) say|can you (?:see|read) (?:this|that))\b""",
)

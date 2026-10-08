package dev.jiadroid.follow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Full-screen eyes that look toward the person and play a conversation emotion. */
class EyesView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 244, 210) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(54, 45, 38)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val iris = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(106, 174, 112) }
    private val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 31, 29) }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val lid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 244, 210) }
    private val brow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(54, 45, 38)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0, 255, 140, 140)
    }

    private var lookX = 0f
    private var lookY = 0f
    private var emotion = Emotion.NEUTRAL
    private var blink = 0f
    private var blinkClosing = false
    private var nextBlinkAt = SystemClock.uptimeMillis() + 2200L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            advanceBlink()
            invalidate()
            handler.postDelayed(this, 33L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    fun setEmotion(next: Emotion) {
        emotion = next
        invalidate()
    }

    fun render(decision: FollowDecision, vertical: Float) {
        // The front camera image is mirrored relative to the face on screen,
        // so a person on the viewer's left is on the right side of that image.
        val targetX = when (decision.command) {
            "TURN LEFT" -> 1f
            "TURN RIGHT" -> -1f
            else -> -(decision.headYaw / 0.5f).coerceIn(-1f, 1f)
        }
        lookX += (targetX - lookX) * 0.35f
        lookY += (vertical.coerceIn(-1f, 1f) - lookY) * 0.25f
        invalidate()
    }

    private fun advanceBlink() {
        val now = SystemClock.uptimeMillis()
        if (!blinkClosing && blink <= 0f && now >= nextBlinkAt) {
            blinkClosing = true
        }
        if (blinkClosing) {
            blink = (blink + 0.28f).coerceAtMost(1f)
            if (blink >= 1f) blinkClosing = false
        } else if (blink > 0f) {
            blink = (blink - 0.22f).coerceAtLeast(0f)
            if (blink <= 0f) {
                nextBlinkAt = now + Random.nextLong(1800L, 4200L)
            }
        }
        // Soft thinking wobble keeps the face alive without a blink.
        if (emotion == Emotion.THINKING && blink <= 0f && (now / 120L) % 2L == 0L) {
            lookX += 0.01f * sin(now / 180.0).toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mood = mood(emotion)
        face.color = mood.face
        lid.color = mood.face
        cheek.color = Color.argb(mood.cheekAlpha, 255, 120, 130)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)
        if (width == 0 || height == 0) return

        val eyeWidth = min(width * 0.34f, height * 0.62f)
        val eyeHeight = min(height * 0.58f, eyeWidth * 0.92f) * mood.heightScale
        val gap = width * 0.055f
        val centerY = height * 0.5f
        val leftX = width * 0.5f - gap / 2f - eyeWidth / 2f
        val rightX = width * 0.5f + gap / 2f + eyeWidth / 2f
        drawEye(canvas, leftX, centerY, eyeWidth, eyeHeight, mood)
        drawEye(canvas, rightX, centerY, eyeWidth, eyeHeight, mood)
        if (mood.cheekAlpha > 0) {
            val r = eyeWidth * 0.18f
            canvas.drawCircle(leftX, centerY + eyeHeight * 0.55f, r, cheek)
            canvas.drawCircle(rightX, centerY + eyeHeight * 0.55f, r, cheek)
        }
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, eyeWidth: Float, eyeHeight: Float, mood: Mood) {
        val open = (1f - blink).coerceIn(0f, 1f) * mood.lidOpen
        val visibleHeight = eyeHeight * open.coerceAtLeast(0.06f)
        val eye = RectF(
            cx - eyeWidth / 2f,
            cy - visibleHeight / 2f,
            cx + eyeWidth / 2f,
            cy + visibleHeight / 2f,
        )
        canvas.drawOval(eye, white)
        canvas.drawOval(eye, outline)

        val irisRadius = min(eyeWidth, eyeHeight) * 0.25f * mood.irisScale
        val travelX = eyeWidth * 0.23f
        val travelY = eyeHeight * 0.2f
        val pupilX = cx + lookX * travelX
        val pupilY = cy + lookY * travelY
        iris.color = mood.iris
        canvas.drawCircle(pupilX, pupilY, irisRadius, iris)
        canvas.drawCircle(pupilX, pupilY, irisRadius * 0.52f * mood.pupilScale, pupil)
        canvas.drawCircle(
            pupilX - irisRadius * 0.2f,
            pupilY - irisRadius * 0.22f,
            irisRadius * 0.15f,
            shine,
        )

        // Upper lid from face color covers the top when blinking or squinting.
        val lidCover = eyeHeight * (1f - open)
        if (lidCover > 1f) {
            canvas.drawRect(
                cx - eyeWidth / 2f - 2f,
                cy - eyeHeight / 2f - 2f,
                cx + eyeWidth / 2f + 2f,
                cy - eyeHeight / 2f + lidCover,
                lid,
            )
        }

        val browPath = Path()
        val browY = cy - eyeHeight * 0.62f + mood.browLift * eyeHeight
        val left = cx - eyeWidth * 0.42f
        val right = cx + eyeWidth * 0.42f
        val midY = browY + mood.browTilt * eyeHeight * 0.12f
        browPath.moveTo(left, browY + mood.browTilt * eyeHeight * 0.08f)
        browPath.quadTo(cx, midY, right, browY - mood.browTilt * eyeHeight * 0.08f)
        canvas.drawPath(browPath, brow)
    }

    private data class Mood(
        val face: Int,
        val iris: Int,
        val lidOpen: Float,
        val irisScale: Float,
        val pupilScale: Float,
        val heightScale: Float,
        val browLift: Float,
        val browTilt: Float,
        val cheekAlpha: Int,
    )

    private fun mood(emotion: Emotion): Mood = when (emotion) {
        Emotion.NEUTRAL -> Mood(Color.rgb(255, 244, 210), Color.rgb(106, 174, 112), 1f, 1f, 1f, 1f, 0f, 0f, 0)
        Emotion.HAPPY -> Mood(Color.rgb(255, 236, 196), Color.rgb(90, 170, 120), 0.78f, 1f, 0.92f, 0.92f, 0.04f, -0.6f, 55)
        Emotion.CURIOUS -> Mood(Color.rgb(255, 244, 210), Color.rgb(100, 160, 200), 1f, 1.12f, 1.15f, 1.05f, 0.08f, 0.35f, 0)
        Emotion.LISTENING -> Mood(Color.rgb(255, 244, 210), Color.rgb(106, 174, 112), 1f, 1.08f, 1.2f, 1f, 0.02f, 0f, 0)
        Emotion.THINKING -> Mood(Color.rgb(245, 240, 230), Color.rgb(120, 150, 130), 0.9f, 0.95f, 0.85f, 0.95f, 0.1f, 0.5f, 0)
        Emotion.CONFUSED -> Mood(Color.rgb(250, 242, 220), Color.rgb(140, 150, 100), 0.95f, 1.05f, 1.05f, 1f, 0.12f, 0.9f, 0)
        Emotion.SAD -> Mood(Color.rgb(235, 238, 245), Color.rgb(90, 130, 150), 0.7f, 0.95f, 1.1f, 0.9f, -0.06f, 0.7f, 0)
        Emotion.EXCITED -> Mood(Color.rgb(255, 230, 190), Color.rgb(80, 190, 110), 1f, 1.18f, 1.25f, 1.08f, 0.1f, -0.4f, 70)
    }
}

package dev.jiadroid.follow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Friendly full-screen eyes used while the robot is following someone. */
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
    private var lookX = 0f
    private var lookY = 0f

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)
        if (width == 0 || height == 0) return

        val eyeWidth = min(width * 0.34f, height * 0.62f)
        val eyeHeight = min(height * 0.58f, eyeWidth * 0.92f)
        val gap = width * 0.055f
        val centerY = height * 0.5f
        val leftX = width * 0.5f - gap / 2f - eyeWidth / 2f
        val rightX = width * 0.5f + gap / 2f + eyeWidth / 2f
        drawEye(canvas, leftX, centerY, eyeWidth, eyeHeight)
        drawEye(canvas, rightX, centerY, eyeWidth, eyeHeight)
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, eyeWidth: Float, eyeHeight: Float) {
        val eye = RectF(
            cx - eyeWidth / 2f,
            cy - eyeHeight / 2f,
            cx + eyeWidth / 2f,
            cy + eyeHeight / 2f,
        )
        canvas.drawOval(eye, white)
        canvas.drawOval(eye, outline)

        val irisRadius = min(eyeWidth, eyeHeight) * 0.25f
        val travelX = eyeWidth * 0.23f
        val travelY = eyeHeight * 0.2f
        val pupilX = cx + lookX * travelX
        val pupilY = cy + lookY * travelY
        canvas.drawCircle(pupilX, pupilY, irisRadius, iris)
        canvas.drawCircle(pupilX, pupilY, irisRadius * 0.52f, pupil)
        canvas.drawCircle(
            pupilX - irisRadius * 0.2f,
            pupilY - irisRadius * 0.22f,
            irisRadius * 0.15f,
            shine,
        )
    }
}

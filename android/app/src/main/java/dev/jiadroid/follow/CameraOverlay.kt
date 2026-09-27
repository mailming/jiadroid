package dev.jiadroid.follow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/** Draws the detected mini-person code on top of the camera preview. */
class CameraOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var corners: List<PointF>? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.person)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.person)
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setMarker(corners: List<PointF>?, imageWidth: Int, imageHeight: Int) {
        this.corners = corners
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val corners = this.corners ?: return
        if (corners.size < 4 || imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) return
        val scale = max(width / imageWidth.toFloat(), height / imageHeight.toFloat())
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        val path = Path()
        val mapped = corners.map { PointF(it.x * scale + dx, it.y * scale + dy) }
        mapped.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        canvas.drawPath(path, stroke)
        val top = mapped.minBy { it.y }
        canvas.drawText("mini person", top.x, top.y - 16f, label)
    }
}

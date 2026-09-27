package dev.jiadroid.follow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Top-down duck that moves from the walk command, as if the phone were the body. */
class DuckView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var poseX = 0f
    private var poseY = 0f
    private var heading = (PI / 2.0).toFloat()
    private var scene = Scene(false, 0f, 0f)
    private var gait = 0f
    private var hfov = (70.0 * PI / 180.0).toFloat()
    private val trail = ArrayList<PointF>()

    private val gridPaint = stroke(R.color.grid, 2f)
    private val conePaint = fill(R.color.cone)
    private val trailPaint = stroke(R.color.trail, 4f)
    private val duckPaint = fill(R.color.duck)
    private val beakPaint = fill(R.color.beak)
    private val inkPaint = fill(R.color.ink)
    private val personPaint = fill(R.color.person)
    private val scalePaint = stroke(R.color.muted, 3f)
    private val labelPaint = text(R.color.muted, 28f)
    private val personText = text(R.color.person, 28f).apply { textAlign = Paint.Align.CENTER }

    private var originX = 0f
    private var originY = 0f
    private var pxPerM = 1f

    fun render(pose: Pose, scene: Scene, gait: Float, hfovRad: Float) {
        poseX = pose.x
        poseY = pose.y
        heading = pose.heading
        this.scene = scene
        this.gait = gait
        this.hfov = hfovRad
        trail.add(PointF(pose.x, pose.y))
        if (trail.size > 80) trail.removeAt(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        originX = width / 2f
        originY = height - 36f
        pxPerM = (height - 56f) / 1.7f
        drawGrid(canvas)
        drawCone(canvas)
        drawTrail(canvas)
        drawMarker(canvas)
        drawDuck(canvas)
        canvas.drawText("simulated duck", 16f, 32f, labelPaint)
        val scaleEnd = 16f + pxPerM
        canvas.drawLine(16f, height - 16f, scaleEnd, height - 16f, scalePaint)
        canvas.drawText("1 m", 16f, height - 22f, labelPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = 0.25f
        var x = floor((poseX - 3f) / spacing) * spacing
        while (x <= poseX + 3f) {
            val start = worldToScreen(x, poseY - 3f)
            val end = worldToScreen(x, poseY + 3f)
            canvas.drawLine(start.x, start.y, end.x, end.y, gridPaint)
            x += spacing
        }
        var y = floor((poseY - 3f) / spacing) * spacing
        while (y <= poseY + 3f) {
            val start = worldToScreen(poseX - 3f, y)
            val end = worldToScreen(poseX + 3f, y)
            canvas.drawLine(start.x, start.y, end.x, end.y, gridPaint)
            y += spacing
        }
    }

    private fun drawCone(canvas: Canvas) {
        val span = hfov / 2f
        val reach = 1.55f
        val origin = duckPoint(0f, 0f)
        val left = duckPoint(cos(span) * reach, -sin(span) * reach)
        val right = duckPoint(cos(span) * reach, sin(span) * reach)
        val path = Path()
        path.moveTo(origin.x, origin.y)
        path.lineTo(left.x, left.y)
        path.lineTo(right.x, right.y)
        path.close()
        canvas.drawPath(path, conePaint)
    }

    private fun drawTrail(canvas: Canvas) {
        if (trail.size < 2) return
        val path = Path()
        trail.forEachIndexed { index, point ->
            val screen = worldToScreen(point.x, point.y)
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        canvas.drawPath(path, trailPaint)
    }

    private fun drawMarker(canvas: Canvas) {
        if (!scene.visible) return
        val reach = scene.distance.coerceAtMost(1.55f)
        val point = duckPoint(cos(scene.angle) * reach, sin(scene.angle) * reach)
        canvas.drawCircle(point.x, point.y, 12f, personPaint)
        canvas.drawText("target", point.x, point.y - 18f, personText)
    }

    private fun drawDuck(canvas: Canvas) {
        val step = 7f * sin(gait)
        val body = listOf(-16f to -12f, 4f to -14f, 8f to 0f, 4f to 14f, -16f to 12f)
        val path = Path()
        body.forEachIndexed { index, (forward, left) ->
            val point = local(forward, left)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        canvas.drawPath(path, duckPaint)
        foot(canvas, -4f + step, 12f)
        foot(canvas, -4f - step, -12f)
        val head = local(16f, 0f)
        canvas.drawCircle(head.x, head.y, 9f, duckPaint)
        val beak = Path()
        listOf(local(22f, -3f), local(30f, 0f), local(22f, 3f)).forEachIndexed { index, point ->
            if (index == 0) beak.moveTo(point.x, point.y) else beak.lineTo(point.x, point.y)
        }
        beak.close()
        canvas.drawPath(beak, beakPaint)
        val eye = local(18f, 4f)
        canvas.drawCircle(eye.x, eye.y, 1.6f, inkPaint)
    }

    private fun foot(canvas: Canvas, forward: Float, side: Float) {
        val center = local(forward, side)
        canvas.drawOval(center.x - 5f, center.y - 3f, center.x + 5f, center.y + 3f, beakPaint)
    }

    private fun worldToScreen(wx: Float, wy: Float): PointF {
        val dx = wx - poseX
        val dy = wy - poseY
        val ahead = dx * cos(heading) + dy * sin(heading)
        val side = dx * sin(heading) - dy * cos(heading)
        return duckPoint(ahead, side)
    }

    private fun duckPoint(ahead: Float, side: Float): PointF {
        return PointF(originX + side * pxPerM, originY - ahead * pxPerM)
    }

    private fun local(forward: Float, left: Float): PointF {
        return PointF(originX - left, originY - forward)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = ContextCompat.getColor(context, color)
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = ContextCompat.getColor(context, color)
    }

    private fun text(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = ContextCompat.getColor(context, color)
        textSize = size
    }
}

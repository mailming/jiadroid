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
    private val trailX = FloatArray(TRAIL_CAP)
    private val trailY = FloatArray(TRAIL_CAP)
    private var trailCount = 0
    private var trailStart = 0

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

    private val scratch = PointF()
    private val conePath = Path()
    private val trailPath = Path()
    private val duckPath = Path()
    private val beakPath = Path()

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
        val slot = (trailStart + trailCount) % TRAIL_CAP
        if (trailCount < TRAIL_CAP) {
            trailCount++
        } else {
            trailStart = (trailStart + 1) % TRAIL_CAP
        }
        trailX[slot] = pose.x
        trailY[slot] = pose.y
        if (visibility == VISIBLE) invalidate()
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
            worldToScreen(x, poseY - 3f, scratch)
            val x0 = scratch.x
            val y0 = scratch.y
            worldToScreen(x, poseY + 3f, scratch)
            canvas.drawLine(x0, y0, scratch.x, scratch.y, gridPaint)
            x += spacing
        }
        var y = floor((poseY - 3f) / spacing) * spacing
        while (y <= poseY + 3f) {
            worldToScreen(poseX - 3f, y, scratch)
            val x0 = scratch.x
            val y0 = scratch.y
            worldToScreen(poseX + 3f, y, scratch)
            canvas.drawLine(x0, y0, scratch.x, scratch.y, gridPaint)
            y += spacing
        }
    }

    private fun drawCone(canvas: Canvas) {
        val span = hfov / 2f
        val reach = 1.55f
        duckPoint(0f, 0f, scratch)
        val ox = scratch.x
        val oy = scratch.y
        duckPoint(cos(span) * reach, -sin(span) * reach, scratch)
        val lx = scratch.x
        val ly = scratch.y
        duckPoint(cos(span) * reach, sin(span) * reach, scratch)
        conePath.reset()
        conePath.moveTo(ox, oy)
        conePath.lineTo(lx, ly)
        conePath.lineTo(scratch.x, scratch.y)
        conePath.close()
        canvas.drawPath(conePath, conePaint)
    }

    private fun drawTrail(canvas: Canvas) {
        if (trailCount < 2) return
        trailPath.reset()
        for (i in 0 until trailCount) {
            val index = (trailStart + i) % TRAIL_CAP
            worldToScreen(trailX[index], trailY[index], scratch)
            if (i == 0) trailPath.moveTo(scratch.x, scratch.y) else trailPath.lineTo(scratch.x, scratch.y)
        }
        canvas.drawPath(trailPath, trailPaint)
    }

    private fun drawMarker(canvas: Canvas) {
        if (!scene.visible) return
        val reach = scene.distance.coerceAtMost(1.55f)
        duckPoint(cos(scene.angle) * reach, sin(scene.angle) * reach, scratch)
        canvas.drawCircle(scratch.x, scratch.y, 12f, personPaint)
        canvas.drawText("target", scratch.x, scratch.y - 18f, personText)
    }

    private fun drawDuck(canvas: Canvas) {
        val step = 7f * sin(gait)
        val body = listOf(-16f to -12f, 4f to -14f, 8f to 0f, 4f to 14f, -16f to 12f)
        duckPath.reset()
        body.forEachIndexed { index, (forward, left) ->
            local(forward, left, scratch)
            if (index == 0) duckPath.moveTo(scratch.x, scratch.y) else duckPath.lineTo(scratch.x, scratch.y)
        }
        duckPath.close()
        canvas.drawPath(duckPath, duckPaint)
        foot(canvas, -4f + step, 12f)
        foot(canvas, -4f - step, -12f)
        local(16f, 0f, scratch)
        canvas.drawCircle(scratch.x, scratch.y, 9f, duckPaint)
        beakPath.reset()
        local(22f, -3f, scratch)
        beakPath.moveTo(scratch.x, scratch.y)
        local(30f, 0f, scratch)
        beakPath.lineTo(scratch.x, scratch.y)
        local(22f, 3f, scratch)
        beakPath.lineTo(scratch.x, scratch.y)
        beakPath.close()
        canvas.drawPath(beakPath, beakPaint)
        local(18f, 4f, scratch)
        canvas.drawCircle(scratch.x, scratch.y, 1.6f, inkPaint)
    }

    private fun foot(canvas: Canvas, forward: Float, side: Float) {
        local(forward, side, scratch)
        canvas.drawOval(scratch.x - 5f, scratch.y - 3f, scratch.x + 5f, scratch.y + 3f, beakPaint)
    }

    private fun worldToScreen(wx: Float, wy: Float, out: PointF) {
        val dx = wx - poseX
        val dy = wy - poseY
        val ahead = dx * cos(heading) + dy * sin(heading)
        val side = dx * sin(heading) - dy * cos(heading)
        duckPoint(ahead, side, out)
    }

    private fun duckPoint(ahead: Float, side: Float, out: PointF) {
        out.set(originX + side * pxPerM, originY - ahead * pxPerM)
    }

    private fun local(forward: Float, left: Float, out: PointF) {
        out.set(originX - left, originY - forward)
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

    companion object {
        private const val TRAIL_CAP = 80
    }
}

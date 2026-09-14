package com.example.motionphotomaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class EditorGestureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var zoom: Float = 1f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set

    var onTransformChanged: ((zoom: Float, panX: Float, panY: Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setTransform(
                newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f),
                newPanX = panX,
                newPanY = panY,
                notify = true,
            )
            return true
        }
    })

    private var lastX = 0f
    private var lastY = 0f
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = resources.displayMetrics.density
    }

    fun setTransform(newZoom: Float, newPanX: Float, newPanY: Float, notify: Boolean = false) {
        zoom = newZoom.coerceIn(1f, 4f)
        panX = newPanX.coerceIn(-1f, 1f)
        panY = newPanY.coerceIn(-1f, 1f)
        invalidate()
        if (notify) onTransformChanged?.invoke(zoom, panX, panY)
    }

    fun reset() = setTransform(1f, 0f, 0f, true)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    val nx = (panX - dx / width.coerceAtLeast(1) * 2f).coerceIn(-1f, 1f)
                    val ny = (panY + dy / height.coerceAtLeast(1) * 2f).coerceIn(-1f, 1f)
                    setTransform(zoom, nx, ny, true)
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
}

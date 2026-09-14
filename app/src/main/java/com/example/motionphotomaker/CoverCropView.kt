package com.example.motionphotomaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * Visual cover cropper. The exact same CropMath state is later used when the
 * JPEG is re-encoded, so the preview and exported cover match.
 */
class CoverCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var zoom: Float = 1f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set

    var targetAspect: Float = 9f / 16f
        set(value) {
            field = value.coerceAtLeast(0.01f)
            invalidate()
        }

    var onTransformChanged: ((zoom: Float, panX: Float, panY: Float) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = resources.displayMetrics.density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setTransform(
                    newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f),
                    newPanX = panX,
                    newPanY = panY,
                    notify = true,
                )
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                reset()
                return true
            }
        },
    )

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        invalidate()
    }

    fun setTransform(
        newZoom: Float,
        newPanX: Float,
        newPanY: Float,
        notify: Boolean = false,
    ) {
        zoom = newZoom.coerceIn(1f, 4f)
        panX = newPanX.coerceIn(-1f, 1f)
        panY = newPanY.coerceIn(-1f, 1f)
        invalidate()
        if (notify) onTransformChanged?.invoke(zoom, panX, panY)
    }

    fun reset() = setTransform(1f, 0f, 0f, notify = true)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val source = bitmap
        if (source != null && width > 0 && height > 0) {
            val crop = CropMath.computePixels(
                sourceWidth = source.width,
                sourceHeight = source.height,
                targetAspect = targetAspect,
                zoom = zoom,
                panX = panX,
                panY = panY,
            )
            val src = RectF(crop.left, crop.top, crop.right, crop.bottom)
            val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(source, src, dst, bitmapPaint)
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
        canvas.drawRect(0.75f, 0.75f, w - 0.75f, h - 0.75f, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return true

        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(dx) > 0.5f || abs(dy) > 0.5f) moved = true
                    val nx = panX - dx / width.coerceAtLeast(1) * 2f
                    val ny = panY - dy / height.coerceAtLeast(1) * 2f
                    setTransform(zoom, nx, ny, notify = true)
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

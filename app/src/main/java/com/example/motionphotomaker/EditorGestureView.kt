package com.example.motionphotomaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Transparent gesture/grid layer used by the video editor.
 *
 * Important: do NOT use TextureView.setTransform() for interactive pan/zoom.
 * ExoPlayer also owns TextureView's internal transform and may replace that
 * matrix when video size/rotation changes. Instead we apply normal Android View
 * properties (scaleX/scaleY/translationX/translationY) to the sibling
 * TextureView. ExoPlayer does not overwrite those properties, so the live
 * preview remains stable while playing, seeking and changing video size.
 */
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

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = resources.displayMetrics.density
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

        // Apply the visible transform immediately. This is intentionally done
        // even for notify=false because the zoom SeekBar calls setTransform()
        // without notification.
        applyPreviewViewTransform()
        invalidate()

        if (notify) onTransformChanged?.invoke(zoom, panX, panY)
    }

    fun reset() = setTransform(1f, 0f, 0f, true)

    /** Reapply after viewport/layout/player changes. */
    fun refreshPreviewTransform() {
        post { applyPreviewViewTransform() }
    }

    private fun findVideoTexture(): TextureView? {
        val group = parent as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is TextureView) return child
        }
        return null
    }

    private fun applyPreviewViewTransform() {
        val texture = findVideoTexture() ?: return
        val viewport = parent as? View ?: return

        // Layout may not have happened yet when a ratio is first selected.
        if (texture.width <= 0 || texture.height <= 0 || viewport.width <= 0 || viewport.height <= 0) {
            texture.post { applyPreviewViewTransform() }
            return
        }

        texture.pivotX = texture.width / 2f
        texture.pivotY = texture.height / 2f
        texture.scaleX = zoom
        texture.scaleY = zoom

        // The parent clips the TextureView. At zoom=1 there is no extra room
        // for panning; as zoom increases, panX/panY select a point within the
        // newly available overflow. Sign matches CropMath/export semantics:
        // panX < 0 means crop center moves left and visible content moves right.
        val overflowX = ((texture.width * zoom - viewport.width) / 2f).coerceAtLeast(0f)
        val overflowY = ((texture.height * zoom - viewport.height) / 2f).coerceAtLeast(0f)
        texture.translationX = -panX * overflowX
        texture.translationY = -panY * overflowY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshPreviewTransform()
    }

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

                    // Drag visible content with the finger. CropMath stores the
                    // crop-center direction, which is the inverse direction.
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

package com.example.motionphotomaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class TimelineTrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class DragMode { NONE, START, END, PLAYHEAD }

    var durationMs: Long = 1L
        set(value) {
            field = value.coerceAtLeast(1L)
            selectedStartMs = selectedStartMs.coerceIn(0L, field)
            selectedEndMs = selectedEndMs.coerceIn(selectedStartMs, field)
            playheadMs = playheadMs.coerceIn(selectedStartMs, selectedEndMs)
            visibleStartMs = 0L
            visibleEndMs = field
            invalidate()
        }

    private var selectedStartMs: Long = 0L
    private var selectedEndMs: Long = 1L
    private var playheadMs: Long = 0L

    private var visibleStartMs: Long = 0L
    private var visibleEndMs: Long = 1L

    var onTrimChanged: ((Long, Long) -> Unit)? = null
    var onPlayheadChanged: ((Long, Boolean) -> Unit)? = null

    private val thumbnails = mutableListOf<Bitmap>()
    private var dragMode = DragMode.NONE
    private val density = resources.displayMetrics.density
    private val handleWidth = 22f * density
    private val playheadWidth = 2f * density

    private var downX = 0f
    private var downEventTime = 0L
    private var lastTapUpTime = 0L

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 9.5f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = density
    }

    fun setThumbnails(bitmaps: List<Bitmap>) {
        thumbnails.clear()
        thumbnails.addAll(bitmaps)
        invalidate()
    }

    fun setTrim(startMs: Long, endMs: Long, notify: Boolean = false) {
        val s = startMs.coerceIn(0L, durationMs)
        val e = endMs.coerceIn(s + 1L, durationMs)
        selectedStartMs = s
        selectedEndMs = e
        playheadMs = playheadMs.coerceIn(s, e)
        ensureSelectionVisible()
        invalidate()
        if (notify) onTrimChanged?.invoke(selectedStartMs, selectedEndMs)
    }

    fun setPlayhead(positionMs: Long) {
        playheadMs = positionMs.coerceIn(selectedStartMs, selectedEndMs)
        invalidate()
    }

    fun resetViewport() {
        visibleStartMs = 0L
        visibleEndMs = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(0xFF202124.toInt())

        val visibleSpan = visibleSpanMs()
        val zoomRatio = durationMs.toDouble() / visibleSpan.toDouble()
        if (thumbnails.isNotEmpty() && zoomRatio <= 2.0) {
            val cellW = w / thumbnails.size
            thumbnails.forEachIndexed { index, bitmap ->
                val dst = RectF(index * cellW, 0f, (index + 1) * cellW + 1f, h)
                val src = centerCropSource(bitmap, dst.width() / dst.height())
                canvas.drawBitmap(bitmap, src, dst, thumbPaint)
            }
        } else {
            // In precision mode the original full-video thumbnails become misleading,
            // so render a clean ruler instead of stretching stale frames.
            val divisions = 8
            for (i in 1 until divisions) {
                val x = w * i / divisions.toFloat()
                canvas.drawLine(x, 0f, x, h, gridPaint)
            }
        }

        val startX = timeToX(selectedStartMs)
        val endX = timeToX(selectedEndMs)
        if (startX > 0f) canvas.drawRect(0f, 0f, startX, h, shadePaint)
        if (endX < w) canvas.drawRect(endX, 0f, w, h, shadePaint)
        canvas.drawRect(startX, 0f, endX, h, selectionPaint)

        canvas.drawRoundRect(
            startX - handleWidth / 2f,
            0f,
            startX + handleWidth / 2f,
            h,
            7f * density,
            7f * density,
            handlePaint,
        )
        canvas.drawRoundRect(
            endX - handleWidth / 2f,
            0f,
            endX + handleWidth / 2f,
            h,
            7f * density,
            7f * density,
            handlePaint,
        )

        val playX = timeToX(playheadMs)
        canvas.drawRect(playX - playheadWidth, 0f, playX + playheadWidth, h, playheadPaint)
        canvas.drawCircle(playX, 7f * density, 5f * density, playheadPaint)

        if (isZoomed()) {
            val hint = "精调 ${format(visibleStartMs)} – ${format(visibleEndMs)} · 双击恢复全片"
            canvas.drawText(hint, 8f * density, 13f * density, hintPaint)
        }

        val label = "${format(selectedStartMs)}  –  ${format(selectedEndMs)}"
        canvas.drawText(label, 8f * density, h - 8f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x
                downEventTime = event.eventTime
                val startX = timeToX(selectedStartMs)
                val endX = timeToX(selectedEndMs)
                dragMode = when {
                    abs(x - startX) <= handleWidth * 1.3f -> DragMode.START
                    abs(x - endX) <= handleWidth * 1.3f -> DragMode.END
                    else -> DragMode.PLAYHEAD
                }
                updateFromTouch(x, true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(x, true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finishedMode = dragMode
                updateFromTouch(x, false)

                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (finishedMode == DragMode.START || finishedMode == DragMode.END) {
                        autoFocusSelection()
                    } else if (
                        finishedMode == DragMode.PLAYHEAD &&
                        event.eventTime - downEventTime <= 240L &&
                        abs(x - downX) <= 10f * density
                    ) {
                        if (event.eventTime - lastTapUpTime <= 320L) {
                            resetViewport()
                            lastTapUpTime = 0L
                        } else {
                            lastTapUpTime = event.eventTime
                        }
                    }
                }

                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun updateFromTouch(x: Float, fromUser: Boolean) {
        val t = xToTime(x)
        val minGap = 100L
        when (dragMode) {
            DragMode.START -> {
                selectedStartMs = t.coerceIn(
                    visibleStartMs.coerceAtLeast(0L),
                    (selectedEndMs - minGap).coerceAtLeast(0L),
                )
                playheadMs = playheadMs.coerceAtLeast(selectedStartMs)
                onTrimChanged?.invoke(selectedStartMs, selectedEndMs)
                onPlayheadChanged?.invoke(selectedStartMs, fromUser)
            }

            DragMode.END -> {
                selectedEndMs = t.coerceIn(
                    (selectedStartMs + minGap).coerceAtMost(durationMs),
                    visibleEndMs.coerceAtMost(durationMs),
                )
                playheadMs = playheadMs.coerceAtMost(selectedEndMs)
                onTrimChanged?.invoke(selectedStartMs, selectedEndMs)
                onPlayheadChanged?.invoke(selectedEndMs, fromUser)
            }

            DragMode.PLAYHEAD -> {
                playheadMs = t.coerceIn(selectedStartMs, selectedEndMs)
                onPlayheadChanged?.invoke(playheadMs, fromUser)
            }

            else -> Unit
        }
        invalidate()
    }

    /**
     * Progressive precision mode: once the selected interval becomes much
     * smaller than the currently visible time window, zoom the ruler around
     * the selection. Repeating the gesture progressively increases precision.
     */
    private fun autoFocusSelection() {
        val selectionSpan = (selectedEndMs - selectedStartMs).coerceAtLeast(1L)
        val currentSpan = visibleSpanMs()

        // Keep coarse editing stable until the selection is clearly narrower.
        if (selectionSpan.toDouble() / currentSpan.toDouble() > 0.60) return

        val targetSpan = max(4_000L, selectionSpan * 3L)
            .coerceAtMost(durationMs)
        if (targetSpan >= (currentSpan * 0.92).toLong()) return

        val center = selectedStartMs + selectionSpan / 2L
        var newStart = center - targetSpan / 2L
        var newEnd = newStart + targetSpan

        if (newStart < 0L) {
            newEnd -= newStart
            newStart = 0L
        }
        if (newEnd > durationMs) {
            val overflow = newEnd - durationMs
            newStart = (newStart - overflow).coerceAtLeast(0L)
            newEnd = durationMs
        }

        visibleStartMs = newStart
        visibleEndMs = newEnd.coerceAtLeast(newStart + 1L)
        ensureSelectionVisible()
        invalidate()
    }

    private fun ensureSelectionVisible() {
        if (selectedStartMs < visibleStartMs || selectedEndMs > visibleEndMs) {
            visibleStartMs = 0L
            visibleEndMs = durationMs
        }
    }

    private fun isZoomed(): Boolean =
        visibleStartMs > 0L || visibleEndMs < durationMs

    private fun visibleSpanMs(): Long =
        (visibleEndMs - visibleStartMs).coerceAtLeast(1L)

    private fun timeToX(timeMs: Long): Float {
        val span = visibleSpanMs().toDouble()
        val fraction = ((timeMs - visibleStartMs).toDouble() / span).coerceIn(0.0, 1.0)
        return width * fraction.toFloat()
    }

    private fun xToTime(x: Float): Long {
        val fraction = x / width.coerceAtLeast(1)
        return (
            visibleStartMs + fraction * visibleSpanMs().toDouble()
        ).toLong().coerceIn(visibleStartMs, visibleEndMs)
    }

    private fun centerCropSource(bitmap: Bitmap, targetAspect: Float): Rect {
        val sourceAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        return if (sourceAspect > targetAspect) {
            val cropW = (bitmap.height * targetAspect).toInt().coerceAtLeast(1)
            val left = (bitmap.width - cropW) / 2
            Rect(left, 0, left + cropW, bitmap.height)
        } else {
            val cropH = (bitmap.width / targetAspect).toInt().coerceAtLeast(1)
            val top = (bitmap.height - cropH) / 2
            Rect(0, top, bitmap.width, top + cropH)
        }
    }

    private fun format(ms: Long): String {
        val totalSeconds = ms / 1000.0
        val minutes = (totalSeconds / 60).toInt()
        val seconds = totalSeconds - minutes * 60
        return if (minutes > 0) {
            "%d:%05.2f".format(minutes, seconds)
        } else {
            "%.2fs".format(seconds)
        }
    }
}

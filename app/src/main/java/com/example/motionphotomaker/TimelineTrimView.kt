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
            invalidate()
        }

    private var selectedStartMs: Long = 0L
    private var selectedEndMs: Long = 1L
    private var playheadMs: Long = 0L

    var onTrimChanged: ((Long, Long) -> Unit)? = null
    var onPlayheadChanged: ((Long, Boolean) -> Unit)? = null

    private val thumbnails = mutableListOf<Bitmap>()
    private var dragMode = DragMode.NONE
    private val density = resources.displayMetrics.density
    private val handleWidth = 22f * density
    private val playheadWidth = 2f * density

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
        invalidate()
        if (notify) onTrimChanged?.invoke(selectedStartMs, selectedEndMs)
    }

    fun setPlayhead(positionMs: Long) {
        playheadMs = positionMs.coerceIn(selectedStartMs, selectedEndMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(0xFF202124.toInt())
        if (thumbnails.isNotEmpty()) {
            val cellW = w / thumbnails.size
            thumbnails.forEachIndexed { index, bitmap ->
                val dst = RectF(index * cellW, 0f, (index + 1) * cellW + 1f, h)
                val src = centerCropSource(bitmap, dst.width() / dst.height())
                canvas.drawBitmap(bitmap, src, dst, thumbPaint)
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

        val label = "${format(selectedStartMs)}  –  ${format(selectedEndMs)}"
        canvas.drawText(label, 8f * density, h - 8f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
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
                updateFromTouch(x, false)
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
                    0L,
                    (selectedEndMs - minGap).coerceAtLeast(0L),
                )
                playheadMs = playheadMs.coerceAtLeast(selectedStartMs)
                onTrimChanged?.invoke(selectedStartMs, selectedEndMs)
                onPlayheadChanged?.invoke(selectedStartMs, fromUser)
            }

            DragMode.END -> {
                selectedEndMs = t.coerceIn(
                    (selectedStartMs + minGap).coerceAtMost(durationMs),
                    durationMs,
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

    private fun timeToX(timeMs: Long): Float =
        width * (timeMs.toDouble() / durationMs.toDouble()).toFloat()

    private fun xToTime(x: Float): Long =
        ((x / width.coerceAtLeast(1)) * durationMs).toLong().coerceIn(0L, durationMs)

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

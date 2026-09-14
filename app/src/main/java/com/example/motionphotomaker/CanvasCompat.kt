package com.example.motionphotomaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Android Canvas only exposes Rect as the source crop for drawBitmap.
 * Keep CoverCropView's floating-point crop math and quantize only at draw time.
 */
fun Canvas.drawBitmap(
    bitmap: Bitmap,
    src: RectF,
    dst: RectF,
    paint: Paint?,
) {
    val left = src.left.roundToInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
    val top = src.top.roundToInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
    val right = src.right.roundToInt().coerceIn(left + 1, bitmap.width)
    val bottom = src.bottom.roundToInt().coerceIn(top + 1, bitmap.height)
    drawBitmap(bitmap, Rect(left, top, right, bottom), dst, paint)
}

package com.example.motionphotomaker

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared crop state. panX/panY use screen coordinates:
 * - panX = -1 left, +1 right
 * - panY = -1 up, +1 down
 */
data class VideoEditParams(
    val startMs: Long,
    val endMs: Long,
    val targetAspect: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

data class CoverEditParams(
    val targetAspect: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

data class CropRectPx(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

object CropMath {
    fun computePixels(
        sourceWidth: Int,
        sourceHeight: Int,
        targetAspect: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
    ): CropRectPx {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(targetAspect > 0f)

        val sourceW = sourceWidth.toFloat()
        val sourceH = sourceHeight.toFloat()
        val sourceAspect = sourceW / sourceH

        var cropW: Float
        var cropH: Float
        if (sourceAspect > targetAspect) {
            cropH = sourceH
            cropW = cropH * targetAspect
        } else {
            cropW = sourceW
            cropH = cropW / targetAspect
        }

        val safeZoom = max(1f, zoom)
        cropW /= safeZoom
        cropH /= safeZoom

        val maxShiftX = ((sourceW - cropW) / 2f).coerceAtLeast(0f)
        val maxShiftY = ((sourceH - cropH) / 2f).coerceAtLeast(0f)
        val centerX = sourceW / 2f + panX.coerceIn(-1f, 1f) * maxShiftX
        val centerY = sourceH / 2f + panY.coerceIn(-1f, 1f) * maxShiftY

        val left = (centerX - cropW / 2f).coerceIn(0f, sourceW - cropW)
        val top = (centerY - cropH / 2f).coerceIn(0f, sourceH - cropH)
        return CropRectPx(
            left = left,
            top = top,
            right = left + cropW,
            bottom = top + cropH,
        )
    }

    /**
     * Output resolution at 1x zoom. Zoom crops a smaller source region but keeps
     * output dimensions stable, so zoom behaves like a real editor rather than
     * reducing the exported resolution.
     */
    fun outputSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetAspect: Float,
    ): Pair<Int, Int> {
        val base = computePixels(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetAspect = targetAspect,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
        )
        val width = evenAtLeast2(base.width.roundToInt())
        val height = evenAtLeast2(base.height.roundToInt())
        return width to height
    }

    private fun evenAtLeast2(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return if (safe % 2 == 0) safe else (safe - 1).coerceAtLeast(2)
    }
}

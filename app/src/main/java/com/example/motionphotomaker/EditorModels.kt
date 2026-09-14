package com.example.motionphotomaker

import kotlin.math.max

/**
 * UI/editor state shared by preview and export.
 * panX/panY are normalized crop-center controls in [-1, 1].
 */
data class VideoEditParams(
    val startMs: Long,
    val endMs: Long,
    val targetAspect: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

data class CropRectNdc(
    val left: Float,
    val right: Float,
    val bottom: Float,
    val top: Float,
)

object CropMath {
    fun compute(
        sourceWidth: Int,
        sourceHeight: Int,
        targetAspect: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
    ): CropRectNdc {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(targetAspect > 0f)

        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        var halfX = 1f
        var halfY = 1f

        if (sourceAspect > targetAspect) {
            halfX = targetAspect / sourceAspect
        } else if (sourceAspect < targetAspect) {
            halfY = sourceAspect / targetAspect
        }

        val safeZoom = max(1f, zoom)
        halfX /= safeZoom
        halfY /= safeZoom

        val maxCenterX = (1f - halfX).coerceAtLeast(0f)
        val maxCenterY = (1f - halfY).coerceAtLeast(0f)
        val cx = panX.coerceIn(-1f, 1f) * maxCenterX
        val cy = panY.coerceIn(-1f, 1f) * maxCenterY

        return CropRectNdc(
            left = (cx - halfX).coerceIn(-1f, 1f),
            right = (cx + halfX).coerceIn(-1f, 1f),
            bottom = (cy - halfY).coerceIn(-1f, 1f),
            top = (cy + halfY).coerceIn(-1f, 1f),
        )
    }
}

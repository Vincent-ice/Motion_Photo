package com.example.motionphotomaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object GridComposer {
    data class Layout(
        val count: Int,
        val columns: Int,
        val rows: Int,
    ) {
        val canvasAspect: Float
            get() = columns.toFloat() / rows.toFloat()
    }

    fun layoutFor(count: Int): Layout = when (count) {
        4 -> Layout(4, 2, 2)
        6 -> Layout(6, 3, 2)
        9 -> Layout(9, 3, 3)
        else -> error("仅支持 4 / 6 / 9 宫格。")
    }

    fun decodeImage(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest.toFloat()
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    /**
     * Returns one independent 1:1 tile. The source image is first center-cropped
     * to the whole-grid aspect (2:2, 3:2 or 3:3), then split row-major.
     */
    fun createTile(source: Bitmap, layout: Layout, index: Int): Bitmap {
        require(index in 0 until layout.count)
        require(source.width > 0 && source.height > 0)

        val sourceW = source.width
        val sourceH = source.height
        val maxTileByWidth = sourceW / layout.columns
        val maxTileByHeight = sourceH / layout.rows
        val tileSize = min(maxTileByWidth, maxTileByHeight).coerceAtLeast(1)

        val canvasW = tileSize * layout.columns
        val canvasH = tileSize * layout.rows
        val originX = ((sourceW - canvasW) / 2).coerceAtLeast(0)
        val originY = ((sourceH - canvasH) / 2).coerceAtLeast(0)

        val row = index / layout.columns
        val col = index % layout.columns
        val x = originX + col * tileSize
        val y = originY + row * tileSize

        val cropped = Bitmap.createBitmap(source, x, y, tileSize, tileSize)
        return if (cropped.config == Bitmap.Config.ARGB_8888 && !cropped.isMutable) {
            cropped.copy(Bitmap.Config.ARGB_8888, false).also {
                if (it !== cropped) cropped.recycle()
            }
        } else {
            cropped.copy(Bitmap.Config.ARGB_8888, false).also {
                if (it !== cropped) cropped.recycle()
            }
        }
    }

    fun encodeTileAsJpeg(tile: Bitmap, quality: Int = 95): ByteArray {
        val flattened = if (tile.hasAlpha()) {
            Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888).also { target ->
                Canvas(target).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(tile, 0f, 0f, null)
                }
            }
        } else {
            tile
        }

        return try {
            ByteArrayOutputStream().use { output ->
                check(flattened.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    "宫格 JPEG 编码失败。"
                }
                output.toByteArray()
            }
        } finally {
            if (flattened !== tile) flattened.recycle()
        }
    }
}

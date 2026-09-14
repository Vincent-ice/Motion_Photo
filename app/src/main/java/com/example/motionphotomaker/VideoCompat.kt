package com.example.motionphotomaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

object VideoCompat {
    data class VideoInfo(
        val width: Int,
        val height: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val rotation: Int,
        val durationUs: Long,
        val videoMime: String,
        val audioMime: String?,
    )

    fun inspect(file: File): VideoInfo {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var width = 0
        var height = 0
        var rotation = 0
        var durationUs = 0L
        var videoMime: String? = null
        var audioMime: String? = null

        try {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        format.getInteger(MediaFormat.KEY_ROTATION)
                    } else {
                        0
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    }
                } else if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                }
            }
        } finally {
            extractor.release()
        }

        val vm = videoMime ?: error("MP4 中没有视频轨。")
        require(width > 0 && height > 0) { "无法读取视频尺寸。" }
        val normalizedRotation = ((rotation % 360) + 360) % 360
        val swap = normalizedRotation == 90 || normalizedRotation == 270
        return VideoInfo(
            width = width,
            height = height,
            displayWidth = if (swap) height else width,
            displayHeight = if (swap) width else height,
            rotation = normalizedRotation,
            durationUs = durationUs,
            videoMime = vm,
            audioMime = audioMime,
        )
    }

    /**
     * Export through a native MediaCodec + OpenGL path. This avoids relying on
     * ExoPlayer/Transformer video effects for the actual encode.
     */
    fun exportEditedForWeChat(
        context: Context,
        input: File,
        output: File,
        params: VideoEditParams,
    ): VideoInfo {
        val sourceInfo = inspect(input)
        require(params.startMs >= 0L) { "开始时间不能小于 0。" }
        require(params.endMs > params.startMs) { "切出点必须晚于切入点。" }

        val durationMs = if (sourceInfo.durationUs > 0L) {
            sourceInfo.durationUs / 1000L
        } else {
            params.endMs
        }
        require(params.startMs < durationMs) { "切入点已经超过原视频时长。" }

        val safeParams = params.copy(endMs = minOf(params.endMs, durationMs))
        NativeVideoTranscoder.transcode(
            input = input,
            output = output,
            params = safeParams,
            sourceInfo = sourceInfo,
        )

        val outputInfo = inspect(output)
        require(outputInfo.videoMime.equals("video/avc", ignoreCase = true)) {
            "导出视频不是 H.264：${outputInfo.videoMime}"
        }
        require(outputInfo.displayWidth > 0 && outputInfo.displayHeight > 0) {
            "导出视频尺寸无效。"
        }
        verifyDecodableFrame(output)
        return outputInfo
    }

    /**
     * Crop/zoom/pan the cover with the same normalized editor semantics used
     * by the on-screen CoverCropView.
     */
    fun cropCover(
        image: ByteArray,
        mimeType: String?,
        params: CoverEditParams,
    ): ByteArray {
        require(params.targetAspect > 0f)
        val oriented = decodeOrientedImage(image, mimeType)

        val crop = CropMath.computePixels(
            sourceWidth = oriented.width,
            sourceHeight = oriented.height,
            targetAspect = params.targetAspect,
            zoom = params.zoom,
            panX = params.panX,
            panY = params.panY,
        )

        var cropH = crop.height.roundToInt().coerceIn(1, oriented.height)
        var cropW = (cropH * params.targetAspect).roundToInt().coerceAtLeast(1)
        if (cropW > oriented.width) {
            cropW = crop.width.roundToInt().coerceIn(1, oriented.width)
            cropH = (cropW / params.targetAspect).roundToInt().coerceIn(1, oriented.height)
        }

        val centerX = (crop.left + crop.right) / 2f
        val centerY = (crop.top + crop.bottom) / 2f
        val cropX = (centerX - cropW / 2f)
            .roundToInt()
            .coerceIn(0, oriented.width - cropW)
        val cropY = (centerY - cropH / 2f)
            .roundToInt()
            .coerceIn(0, oriented.height - cropH)

        val cropped = Bitmap.createBitmap(oriented, cropX, cropY, cropW, cropH)
        val jpegBitmap = if (cropped.hasAlpha()) {
            Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888).also { matte ->
                Canvas(matte).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(cropped, 0f, 0f, null)
                }
            }
        } else {
            cropped
        }

        val out = ByteArrayOutputStream()
        require(jpegBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
            "JPEG 封面重新编码失败。"
        }

        if (jpegBitmap !== cropped) jpegBitmap.recycle()
        if (cropped !== oriented) cropped.recycle()
        oriented.recycle()
        return out.toByteArray()
    }

    private fun decodeOrientedImage(image: ByteArray, mimeType: String?): Bitmap {
        val isJpeg = mimeType.equals("image/jpeg", ignoreCase = true) ||
            (image.size >= 2 && (image[0].toInt() and 0xFF) == 0xFF &&
                (image[1].toInt() and 0xFF) == 0xD8)
        val isPng = mimeType.equals("image/png", ignoreCase = true) ||
            (image.size >= 8 &&
                (image[0].toInt() and 0xFF) == 0x89 && image[1].toInt() == 0x50 &&
                image[2].toInt() == 0x4E && image[3].toInt() == 0x47 &&
                image[4].toInt() == 0x0D && image[5].toInt() == 0x0A &&
                image[6].toInt() == 0x1A && image[7].toInt() == 0x0A)
        require(isJpeg || isPng) { "封面不是有效的 JPEG 或 PNG。" }

        val orientation = if (isJpeg) {
            runCatching {
                ExifInterface(ByteArrayInputStream(image)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } else {
            ExifInterface.ORIENTATION_NORMAL
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码 JPEG / PNG 封面。" }

        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) {
            sample *= 2
        }

        val decoded = BitmapFactory.decodeByteArray(
            image,
            0,
            image.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("无法解码 JPEG / PNG 封面。")

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(90f)
            }
        }

        return if (!matrix.isIdentity) {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                matrix,
                true,
            ).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
    }

    private fun verifyDecodableFrame(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            val timeUs = if (durationMs > 0) {
                (durationMs * 1000L / 2L).coerceAtLeast(0L)
            } else {
                0L
            }
            val frame = retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
            require(frame != null) { "导出视频无法解码出画面。" }
            frame.recycle()
        } finally {
            retriever.release()
        }
    }
}

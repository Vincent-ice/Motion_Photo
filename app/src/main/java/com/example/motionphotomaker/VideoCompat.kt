package com.example.motionphotomaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

@UnstableApi
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
                    rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = format.getLong(MediaFormat.KEY_DURATION)
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
     * Re-encodes the selected range with the exact visual crop used by preview.
     * Output is forced to H.264 + AAC for broad Android/WeChat compatibility.
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
        if (sourceInfo.durationUs > 0L) {
            require(params.startMs * 1000L < sourceInfo.durationUs) { "切入点已经超过原视频时长。" }
        }

        val endMs = if (sourceInfo.durationUs > 0L) {
            minOf(params.endMs, sourceInfo.durationUs / 1000L)
        } else params.endMs

        val crop = CropMath.compute(
            sourceWidth = sourceInfo.displayWidth,
            sourceHeight = sourceInfo.displayHeight,
            targetAspect = params.targetAspect,
            zoom = params.zoom,
            panX = params.panX,
            panY = params.panY,
        )
        val cropEffect: Effect = Crop(crop.left, crop.right, crop.bottom, crop.top)

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(params.startMs)
            .setEndPositionMs(endMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(input))
            .setClippingConfiguration(clipping)
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), listOf(cropEffect)))
            .build()

        if (output.exists()) output.delete()
        val thread = HandlerThread("motion-photo-export").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val transformer = Transformer.Builder(context.applicationContext)
            .setLooper(thread.looper)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEnsureFileStartsOnVideoFrameEnabled(true)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    latch.countDown()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    failure.set(exportException)
                    latch.countDown()
                }
            })
            .build()

        handler.post {
            runCatching { transformer.start(edited, output.absolutePath) }
                .onFailure {
                    failure.set(it)
                    latch.countDown()
                }
        }

        val finished = latch.await(15, TimeUnit.MINUTES)
        if (!finished) {
            handler.post { runCatching { transformer.cancel() } }
            thread.quitSafely()
            error("视频导出超时。")
        }
        thread.quitSafely()
        failure.get()?.let { throw IllegalStateException("视频导出失败：${it.message}", it) }
        require(output.exists() && output.length() > 0L) { "视频导出结果为空。" }
        return inspect(output)
    }

    fun cropCoverToAspect(jpeg: ByteArray, targetAspect: Float): ByteArray {
        require(targetAspect > 0f)
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码 JPEG 封面。" }
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("无法解码 JPEG 封面。")

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
        }
        val oriented = if (!matrix.isIdentity) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else decoded

        val sourceRatio = oriented.width.toFloat() / oriented.height.toFloat()
        val cropW: Int
        val cropH: Int
        val cropX: Int
        val cropY: Int
        if (abs(sourceRatio - targetAspect) < 0.001f) {
            cropW = oriented.width
            cropH = oriented.height
            cropX = 0
            cropY = 0
        } else if (sourceRatio > targetAspect) {
            cropH = oriented.height
            cropW = (cropH * targetAspect).roundToInt().coerceAtMost(oriented.width)
            cropX = (oriented.width - cropW) / 2
            cropY = 0
        } else {
            cropW = oriented.width
            cropH = (cropW / targetAspect).roundToInt().coerceAtMost(oriented.height)
            cropX = 0
            cropY = (oriented.height - cropH) / 2
        }

        val cropped = Bitmap.createBitmap(oriented, cropX, cropY, cropW, cropH)
        val out = ByteArrayOutputStream()
        require(cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "JPEG 封面重新编码失败。" }
        if (cropped !== oriented) cropped.recycle()
        oriented.recycle()
        return out.toByteArray()
    }
}

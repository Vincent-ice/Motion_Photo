package com.example.motionphotomaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
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
                    } else 0
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

    fun remuxForWeChat(
        input: File,
        output: File,
        requestedStartUs: Long,
        requestedDurationUs: Long,
    ): VideoInfo {
        val info = inspect(input)
        require(info.videoMime == "video/avc") {
            "微信兼容模式目前要求 H.264/AVC 视频；当前为 ${info.videoMime}。"
        }
        require(info.audioMime == null || info.audioMime == "audio/mp4a-latm") {
            "微信兼容模式目前只接受 AAC 音频；当前为 ${info.audioMime}。"
        }
        require(requestedStartUs >= 0L) { "开始时间不能小于 0 秒。" }
        require(requestedDurationUs in 100_000L..3_000_000L) {
            "动态时长需在 0.1～3.0 秒之间。"
        }
        if (info.durationUs > 0) {
            require(requestedStartUs < info.durationUs) { "开始时间已经超过视频总时长。" }
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (info.rotation != 0) muxer.setOrientationHint(info.rotation)

        val trackMap = mutableMapOf<Int, Int>()
        var maxInputSize = 1024 * 1024
        var muxerStarted = false
        try {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val keep = mime == "video/avc" || mime == "audio/mp4a-latm"
                if (!keep) continue
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = max(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            require(trackMap.isNotEmpty()) { "没有可写入的音视频轨。" }
            muxer.start()
            muxerStarted = true

            // 无损 remux 必须从同步帧附近开始，因此手动起点会吸附到最近同步帧。
            extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val actualStartUs = extractor.sampleTime.coerceAtLeast(0L)
            val endUs = actualStartUs + requestedDurationUs

            val buffer = ByteBuffer.allocateDirect(max(maxInputSize, 4 * 1024 * 1024))
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val inputTrack = extractor.sampleTrackIndex
                val outputTrack = trackMap[inputTrack]
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime > endUs) break
                if (outputTrack != null && sampleTime >= actualStartUs) {
                    val pts = sampleTime - actualStartUs
                    bufferInfo.set(0, size, pts.coerceAtLeast(0L), extractor.sampleFlags)
                    muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                }
                if (!extractor.advance()) break
            }
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            extractor.release()
        }
        require(output.length() > 0) { "视频兼容化失败。" }
        return inspect(output)
    }

    fun cropCoverToVideoAspect(jpeg: ByteArray, targetWidth: Int, targetHeight: Int): ByteArray {
        require(targetWidth > 0 && targetHeight > 0)
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码 JPEG 封面。" }
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: error("无法解码 JPEG 封面。")

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

        val targetRatio = targetWidth.toDouble() / targetHeight.toDouble()
        val sourceRatio = oriented.width.toDouble() / oriented.height.toDouble()
        val cropW: Int
        val cropH: Int
        val cropX: Int
        val cropY: Int
        if (abs(sourceRatio - targetRatio) < 0.001) {
            cropW = oriented.width
            cropH = oriented.height
            cropX = 0
            cropY = 0
        } else if (sourceRatio > targetRatio) {
            cropH = oriented.height
            cropW = (cropH * targetRatio).roundToInt().coerceAtMost(oriented.width)
            cropX = (oriented.width - cropW) / 2
            cropY = 0
        } else {
            cropW = oriented.width
            cropH = (cropW / targetRatio).roundToInt().coerceAtMost(oriented.height)
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

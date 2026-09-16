@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.motionphotomaker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Brightness
import androidx.media3.effect.Presentation
import androidx.media3.effect.TimestampWrapper
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import kotlin.math.min

/** One still frame in a slideshow project. */
data class SlideshowFrame(
    val uri: Uri,
    val durationMs: Long,
)

data class SlideshowExportSpec(
    val frames: List<SlideshowFrame>,
    val musicUri: Uri?,
    val width: Int,
    val height: Int,
    val fadeEnabled: Boolean = true,
)

/**
 * Exports still images as a H.264/AAC MP4 using Media3 Composition.
 * Image durations are independent, background music loops (or is clipped)
 * to the slideshow duration, and every image is center-cropped to the same
 * output frame used by the preview.
 */
class SlideshowExporter(private val context: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var tempFile: File? = null

    fun export(
        spec: SlideshowExportSpec,
        onCompleted: (Uri) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        require(spec.frames.isNotEmpty()) { "至少需要一张图片。" }
        require(spec.width > 0 && spec.height > 0) { "无效输出尺寸。" }
        check(transformer == null) { "已有导出任务正在运行。" }

        val file = File(context.cacheDir, "slideshow_${System.currentTimeMillis()}.mp4")
        if (file.exists()) file.delete()
        tempFile = file

        val presentation = Presentation.createForWidthAndHeight(
            spec.width,
            spec.height,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
        )

        val videoItems = spec.frames.map { frame ->
            val durationMs = frame.durationMs.coerceIn(500L, 10_000L)
            val effects = mutableListOf<Effect>(presentation)
            if (spec.fadeEnabled) effects += fadeToBlackEffects(durationMs)

            val item = MediaItem.Builder()
                .setUri(frame.uri)
                .setImageDurationMs(durationMs)
                .build()

            EditedMediaItem.Builder(item)
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), effects))
                .build()
        }

        val sequences = mutableListOf(
            EditedMediaItemSequence.withVideoFrom(videoItems),
        )

        spec.musicUri?.let { musicUri ->
            val music = EditedMediaItem.Builder(MediaItem.fromUri(musicUri)).build()
            val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(music))
                .buildUpon()
                .setIsLooping(true)
                .build()
            sequences += audioSequence
        }

        val composition = Composition.Builder(sequences).build()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                transformer = null
                io.execute {
                    try {
                        val uri = publishVideo(file, spec.width, spec.height)
                        file.delete()
                        tempFile = null
                        android.os.Handler(context.mainLooper).post { onCompleted(uri) }
                    } catch (t: Throwable) {
                        file.delete()
                        tempFile = null
                        android.os.Handler(context.mainLooper).post { onError(t) }
                    }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                transformer = null
                file.delete()
                tempFile = null
                onError(exportException)
            }
        }

        val builder = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setPortraitEncodingEnabled(true)
            .addListener(listener)
        if (spec.musicUri != null) {
            builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
        }
        transformer = builder.build()

        transformer!!.start(composition, file.absolutePath)
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
        tempFile?.delete()
        tempFile = null
    }

    fun release() {
        cancel()
        io.shutdownNow()
    }

    /**
     * Media3 does not currently provide cross-fades between Composition items.
     * This implements a short stepped fade-to-black at each still's edges,
     * which keeps export/preview behavior deterministic without overlapping clips.
     */
    private fun fadeToBlackEffects(durationMs: Long): List<Effect> {
        val fadeMs = min(240L, durationMs / 5L)
        if (fadeMs < 80L) return emptyList()
        val fadeUs = fadeMs * 1000L
        val durationUs = durationMs * 1000L
        val steps = 4
        val result = mutableListOf<Effect>()

        for (step in 0 until steps) {
            val start = fadeUs * step / steps
            val end = fadeUs * (step + 1) / steps
            val darkness = -0.75f + step * (0.75f / (steps - 1).toFloat())
            result += TimestampWrapper(Brightness(darkness), start, end)
        }
        for (step in 0 until steps) {
            val start = durationUs - fadeUs + fadeUs * step / steps
            val end = durationUs - fadeUs + fadeUs * (step + 1) / steps
            val darkness = -step * (0.75f / (steps - 1).toFloat())
            result += TimestampWrapper(Brightness(darkness), start, end)
        }
        return result
    }

    private fun publishVideo(source: File, width: Int, height: Int): Uri {
        val resolver = context.contentResolver
        val displayName = "SLIDESHOW_${System.currentTimeMillis()}_${width}x${height}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/MotionPhotoMaker",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建视频媒体文件。")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: error("无法写入视频文件。")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}

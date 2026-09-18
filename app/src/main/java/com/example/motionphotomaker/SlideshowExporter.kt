@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.motionphotomaker

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Brightness
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
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One still frame in a slideshow project. */
data class SlideshowFrame(
    val uri: Uri,
    val durationMs: Long,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
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
 *
 * Every source image is first rendered through CropMath into an exact-size
 * temporary JPEG. The preview uses the same CropMath state, so per-slide
 * zoom/pan is deterministic and does not depend on Media3 effect coordinates.
 */
class SlideshowExporter(private val context: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    @Volatile private var cancelled = false
    private var transformer: Transformer? = null
    private var tempFile: File? = null
    private var preparedDir: File? = null

    fun export(
        spec: SlideshowExportSpec,
        onCompleted: (Uri) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        require(spec.frames.isNotEmpty()) { "至少需要一张图片。" }
        require(spec.width > 0 && spec.height > 0) { "无效输出尺寸。" }
        check(!busy) { "已有导出任务正在运行。" }

        busy = true
        cancelled = false

        val file = File(context.cacheDir, "slideshow_${System.currentTimeMillis()}.mp4")
        if (file.exists()) file.delete()
        tempFile = file

        val frameDir = File(context.cacheDir, "slideshow_frames_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        preparedDir = frameDir

        io.execute {
            try {
                val preparedUris = spec.frames.mapIndexed { index, frame ->
                    check(!cancelled) { "导出已取消。" }
                    Uri.fromFile(prepareFrame(frame, index, spec.width, spec.height, frameDir))
                }

                mainHandler.post {
                    if (cancelled) {
                        finishCleanup(file, frameDir)
                        busy = false
                        return@post
                    }
                    startTransformer(spec, preparedUris, file, frameDir, onCompleted, onError)
                }
            } catch (t: Throwable) {
                finishCleanup(file, frameDir)
                busy = false
                if (!cancelled) mainHandler.post { onError(t) }
            }
        }
    }

    private fun startTransformer(
        spec: SlideshowExportSpec,
        preparedUris: List<Uri>,
        outputFile: File,
        frameDir: File,
        onCompleted: (Uri) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val videoItems = preparedUris.mapIndexed { index, uri ->
            val frame = spec.frames[index]
            val durationMs = frame.durationMs.coerceIn(500L, 10_000L)
            val effects = mutableListOf<Effect>()
            if (spec.fadeEnabled) effects += fadeToBlackEffects(durationMs)

            val item = MediaItem.Builder()
                .setUri(uri)
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
                        val uri = publishVideo(outputFile, spec.width, spec.height)
                        finishCleanup(outputFile, frameDir)
                        busy = false
                        mainHandler.post { onCompleted(uri) }
                    } catch (t: Throwable) {
                        finishCleanup(outputFile, frameDir)
                        busy = false
                        mainHandler.post { onError(t) }
                    }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                transformer = null
                busy = false
                finishCleanup(outputFile, frameDir)
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
        transformer!!.start(composition, outputFile.absolutePath)
    }

    private fun prepareFrame(
        frame: SlideshowFrame,
        index: Int,
        outputWidth: Int,
        outputHeight: Int,
        directory: File,
    ): File {
        val source = decodeForExport(frame.uri, outputWidth, outputHeight, frame.zoom)
        try {
            val targetAspect = outputWidth.toFloat() / outputHeight.toFloat()
            val crop = CropMath.computePixels(
                sourceWidth = source.width,
                sourceHeight = source.height,
                targetAspect = targetAspect,
                zoom = frame.zoom,
                panX = frame.panX,
                panY = frame.panY,
            )

            val rendered = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(rendered)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(
                    source,
                    RectF(crop.left, crop.top, crop.right, crop.bottom),
                    RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )

                val output = File(directory, "frame_${"%03d".format(index)}.jpg")
                FileOutputStream(output).use { stream ->
                    check(rendered.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                        "无法编码第 ${index + 1} 张图片。"
                    }
                }
                return output
            } finally {
                rendered.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun decodeForExport(
        uri: Uri,
        outputWidth: Int,
        outputHeight: Int,
        zoom: Float,
    ): Bitmap {
        val imageSource = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sourceMax = max(info.size.width, info.size.height).coerceAtLeast(1)
            val requestedMax = (
                max(outputWidth, outputHeight) * zoom.coerceIn(1f, 4f) * 1.15f
            ).roundToInt().coerceIn(max(outputWidth, outputHeight), 4096)
            val sample = ceil(sourceMax.toDouble() / requestedMax.toDouble())
                .toInt()
                .coerceAtLeast(1)
            decoder.setTargetSampleSize(sample)
        }
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
        transformer = null
        busy = false
        tempFile?.delete()
        tempFile = null
        preparedDir?.deleteRecursively()
        preparedDir = null
    }

    fun release() {
        cancel()
        io.shutdownNow()
    }

    private fun finishCleanup(outputFile: File, frameDir: File) {
        outputFile.delete()
        frameDir.deleteRecursively()
        if (tempFile == outputFile) tempFile = null
        if (preparedDir == frameDir) preparedDir = null
    }

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
        val nowMs = System.currentTimeMillis()
        val nowSeconds = nowMs / 1000L
        val displayName = "SLIDESHOW_${nowMs}_${width}x${height}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/MotionPhotoMaker",
            )
            // Some gallery apps prefer DATE_TAKEN while others sort by
            // DATE_ADDED / DATE_MODIFIED. Set all three explicitly so an
            // encoder/container timestamp cannot make a fresh slideshow look
            // like it was created in 2005.
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
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
                ContentValues().apply {
                    put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
                    put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                },
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

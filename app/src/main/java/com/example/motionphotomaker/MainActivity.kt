package com.example.motionphotomaker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(16, 17, 20)
    private val panel = Color.rgb(30, 32, 37)
    private val panel2 = Color.rgb(42, 45, 52)
    private val textPrimary = Color.rgb(245, 246, 248)
    private val textSecondary = Color.rgb(174, 178, 188)
    private val accent = Color.rgb(255, 196, 46)

    private var coverUri: Uri? = null
    private var videoUri: Uri? = null
    private var resultUri: Uri? = null
    private var coverBitmap: Bitmap? = null

    private var sourceDurationMs = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var targetAspect = 9f / 16f

    private var trimStartMs = 0L
    private var trimEndMs = 1L

    private var videoZoom = 1f
    private var videoPanX = 0f
    private var videoPanY = 0f

    private var coverZoom = 1f
    private var coverPanX = 0f
    private var coverPanY = 0f

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer

    private lateinit var previewOuter: FrameLayout
    private lateinit var previewViewport: FrameLayout
    private lateinit var videoTexture: TextureView
    private lateinit var gestureView: EditorGestureView
    private lateinit var timeline: TimelineTrimView
    private lateinit var videoInfo: TextView
    private lateinit var trimInfo: TextView
    private lateinit var transformInfo: TextView
    private lateinit var playButton: Button
    private lateinit var videoZoomSeek: SeekBar

    private lateinit var coverOuter: FrameLayout
    private lateinit var coverViewport: FrameLayout
    private lateinit var coverCropView: CoverCropView
    private lateinit var coverName: TextView
    private lateinit var coverTransformInfo: TextView
    private lateinit var coverZoomSeek: SeekBar

    private lateinit var generateButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var openResultButton: Button

    private val aspectButtons = mutableListOf<Pair<Button, Float?>>()

    private val playbackTicker = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying && ::timeline.isInitialized) {
                val pos = player.currentPosition
                timeline.setPlayhead(pos)
                if (pos >= trimEndMs) player.seekTo(trimStartMs)
            }
            mainHandler.postDelayed(this, 40L)
        }
    }

    private val coverPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri ?: return@registerForActivityResult
            coverUri = uri
            resultUri = null
            openResultButton.isEnabled = false
            coverName.text = displayName(uri) ?: "已选择 JPEG 封面"
            loadCoverPreview(uri)
            updateGenerateEnabled()
        }

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri ?: return@registerForActivityResult
            videoUri = uri
            resultUri = null
            openResultButton.isEnabled = false
            loadVideo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContentView(buildUi())

        player.setVideoTextureView(videoTexture)
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playButton.text = if (isPlaying) "暂停" else "播放选区"
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    playButton.text = if (player.isPlaying) "暂停" else "播放选区"
                }
            },
        )
        mainHandler.post(playbackTicker)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(16), dp(16), dp(36))
        }

        root.addView(
            TextView(this).apply {
                text = "Motion Photo Studio"
                textSize = 28f
                setTextColor(textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        root.addView(
            label("可视化视频取景 · 封面裁剪 · 时间裁剪 · 微信兼容输出", 13f).apply {
                setPadding(0, dp(4), 0, dp(12))
            },
        )

        root.addView(sectionTitle("视频预览"))
        val videoCard = card()
        previewOuter = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        videoCard.addView(
            previewOuter,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)),
        )

        previewViewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            clipToPadding = true
        }
        previewOuter.addView(
            previewViewport,
            FrameLayout.LayoutParams(dp(214), dp(380), Gravity.CENTER),
        )

        videoTexture = TextureView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        previewViewport.addView(
            videoTexture,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        gestureView = EditorGestureView(this).apply {
            onTransformChanged = { z, x, y ->
                videoZoom = z
                videoPanX = x
                videoPanY = y
                if (::videoZoomSeek.isInitialized) {
                    videoZoomSeek.progress = ((z - 1f) * 100f).roundToInt()
                }
                updateVideoTransformInfo()
                applyVideoPreviewTransform()
            }
        }
        previewViewport.addView(
            gestureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val playRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        playButton = secondaryButton("播放选区").apply {
            setOnClickListener {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.currentPosition !in trimStartMs..trimEndMs) {
                        player.seekTo(trimStartMs)
                    }
                    player.play()
                }
            }
        }
        playRow.addView(playButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        playRow.addView(space(dp(8)))
        playRow.addView(
            secondaryButton("选择视频").apply {
                setOnClickListener {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f),
        )
        videoCard.addView(playRow)

        videoInfo = label("尚未选择视频", 12f)
        videoCard.addView(videoInfo)
        videoCard.addView(
            label(
                "直接在画面上单指拖动取景，双指缩放；双击恢复 1× 居中。这里的变化会立即反映到真实视频预览。",
                12f,
            ),
        )
        root.addView(videoCard)

        root.addView(sectionTitle("切入 / 切出"))
        val timelineCard = card()
        timeline = TimelineTrimView(this).apply {
            durationMs = 1L
            setTrim(0L, 1L)
            onTrimChanged = { start, end ->
                trimStartMs = start
                trimEndMs = end
                trimInfo.text =
                    "${formatTime(start)}  →  ${formatTime(end)}   ·   ${"%.2f".format((end - start) / 1000.0)} s"
                if (player.currentPosition !in start..end) player.seekTo(start)
            }
            onPlayheadChanged = { position, fromUser ->
                if (fromUser) player.seekTo(position)
            }
        }
        timelineCard.addView(
            timeline,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)),
        )
        trimInfo = label("拖动左右手柄设置切入和切出", 12f).apply {
            setPadding(0, dp(8), 0, 0)
        }
        timelineCard.addView(trimInfo)
        root.addView(timelineCard)

        root.addView(sectionTitle("画面比例"))
        val ratioCard = card()
        val ratioScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val ratioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            "原始" to null,
            "1:1" to 1f,
            "4:3" to (4f / 3f),
            "3:4" to (3f / 4f),
            "16:9" to (16f / 9f),
            "9:16" to (9f / 16f),
        ).forEach { (name, ratio) ->
            val button = chipButton(name)
            button.setOnClickListener {
                targetAspect =
                    ratio ?: if (sourceHeight > 0) {
                        sourceWidth.toFloat() / sourceHeight.toFloat()
                    } else {
                        9f / 16f
                    }

                resetVideoTransform()
                resetCoverTransform()
                coverCropView.targetAspect = targetAspect
                updateAspectButtons(button)
                updatePreviewViewportAspect()
                updateCoverViewportAspect()
                applyVideoPreviewTransform()
            }
            aspectButtons += button to ratio
            ratioRow.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(42),
                ).apply {
                    marginEnd = dp(8)
                },
            )
        }
        ratioScroll.addView(ratioRow)
        ratioCard.addView(ratioScroll)
        root.addView(ratioCard)

        root.addView(sectionTitle("视频缩放与取景"))
        val transformCard = card()
        videoZoomSeek = zoomSeekBar { z ->
            videoZoom = z
            gestureView.setTransform(videoZoom, videoPanX, videoPanY)
            updateVideoTransformInfo()
            applyVideoPreviewTransform()
        }
        transformCard.addView(zoomRow(videoZoomSeek))
        transformInfo = label("缩放 1.00× · X +0% · Y +0%", 12f)
        transformCard.addView(transformInfo)
        transformCard.addView(
            secondaryButton("重置视频取景").apply {
                setOnClickListener { resetVideoTransform() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44),
            ).apply {
                topMargin = dp(8)
            },
        )
        root.addView(transformCard)

        root.addView(sectionTitle("静态封面裁剪"))
        val coverCard = card()
        coverOuter = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        coverCard.addView(
            coverOuter,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)),
        )
        coverViewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        coverOuter.addView(
            coverViewport,
            FrameLayout.LayoutParams(dp(180), dp(320), Gravity.CENTER),
        )

        coverCropView = CoverCropView(this).apply {
            targetAspect = this@MainActivity.targetAspect
            onTransformChanged = { z, x, y ->
                coverZoom = z
                coverPanX = x
                coverPanY = y
                if (::coverZoomSeek.isInitialized) {
                    coverZoomSeek.progress = ((z - 1f) * 100f).roundToInt()
                }
                updateCoverTransformInfo()
            }
        }
        coverViewport.addView(
            coverCropView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        coverCard.addView(
            secondaryButton("选择 JPEG 封面").apply {
                setOnClickListener {
                    coverPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44),
            ).apply {
                topMargin = dp(10)
            },
        )
        coverName = label("尚未选择封面", 12f)
        coverCard.addView(coverName)

        coverZoomSeek = zoomSeekBar { z ->
            coverZoom = z
            coverCropView.setTransform(coverZoom, coverPanX, coverPanY)
            updateCoverTransformInfo()
        }
        coverCard.addView(zoomRow(coverZoomSeek))
        coverTransformInfo = label("封面缩放 1.00× · X +0% · Y +0%", 12f)
        coverCard.addView(coverTransformInfo)
        coverCard.addView(
            label(
                "封面同样支持单指拖动、双指缩放和双击重置；最终 JPEG 会按照这里看到的区域裁剪。",
                12f,
            ),
        )
        coverCard.addView(
            secondaryButton("重置封面取景").apply {
                setOnClickListener { resetCoverTransform() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44),
            ).apply {
                topMargin = dp(8)
            },
        )
        root.addView(coverCard)

        generateButton = primaryButton("生成 Motion Photo").apply {
            isEnabled = false
            setOnClickListener { generateMotionPhoto() }
        }
        root.addView(
            generateButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54),
            ).apply {
                topMargin = dp(22)
            },
        )

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6),
            ).apply {
                topMargin = dp(10)
            },
        )

        statusText = label(
            "选择视频和 JPEG 封面后即可生成。视频导出现在使用独立 MediaCodec + OpenGL 管线。",
            13f,
        ).apply {
            setPadding(0, dp(10), 0, dp(8))
            setTextIsSelectable(true)
        }
        root.addView(statusText)

        openResultButton = secondaryButton("在相册中打开结果").apply {
            isEnabled = false
            setOnClickListener { openGeneratedPhoto() }
        }
        root.addView(
            openResultButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ),
        )

        updateAspectButtons(aspectButtons.last().first)
        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun loadVideo(uri: Uri) {
        player.pause()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.seekTo(0L)

        val meta = readVideoMeta(uri)
        sourceDurationMs = meta.durationMs.coerceAtLeast(1L)
        sourceWidth = meta.displayWidth
        sourceHeight = meta.displayHeight
        targetAspect =
            if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight.toFloat()
            else 9f / 16f

        trimStartMs = 0L
        trimEndMs = sourceDurationMs
        timeline.durationMs = sourceDurationMs
        timeline.setTrim(trimStartMs, trimEndMs)
        timeline.setPlayhead(trimStartMs)
        trimInfo.text =
            "${formatTime(trimStartMs)}  →  ${formatTime(trimEndMs)}   ·   ${"%.2f".format((trimEndMs - trimStartMs) / 1000.0)} s"

        videoInfo.text =
            "${displayName(uri) ?: "视频"}  ·  ${sourceWidth}×${sourceHeight}  ·  ${formatTime(sourceDurationMs)}"

        resetVideoTransform()
        resetCoverTransform()
        coverCropView.targetAspect = targetAspect
        updateAspectButtons(aspectButtons.first().first)
        updatePreviewViewportAspect()
        updateCoverViewportAspect()
        applyVideoPreviewTransform()
        updateGenerateEnabled()

        worker.execute {
            val thumbs = runCatching {
                ThumbnailLoader.load(applicationContext, uri, sourceDurationMs, 10)
            }.getOrDefault(emptyList())
            runOnUiThread { timeline.setThumbnails(thumbs) }
        }
    }

    private fun loadCoverPreview(uri: Uri) {
        worker.execute {
            val decoded = runCatching {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    val maxSide = max(info.size.width, info.size.height)
                    if (maxSide > 2048) {
                        val scale = 2048f / maxSide.toFloat()
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()

            runOnUiThread {
                if (decoded == null) {
                    statusText.text = "封面预览解码失败，请换一张 JPEG。"
                    return@runOnUiThread
                }
                coverBitmap?.recycle()
                coverBitmap = decoded
                coverCropView.setBitmap(decoded)
                coverCropView.targetAspect = targetAspect
                resetCoverTransform()
                updateCoverViewportAspect()
            }
        }
    }

    private fun applyVideoPreviewTransform() {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        videoTexture.post {
            val w = videoTexture.width.toFloat()
            val h = videoTexture.height.toFloat()
            if (w <= 0f || h <= 0f) return@post

            val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
            val viewportAspect = w / h

            val baseScaleX =
                if (sourceAspect > viewportAspect) sourceAspect / viewportAspect else 1f
            val baseScaleY =
                if (sourceAspect < viewportAspect) viewportAspect / sourceAspect else 1f

            val sx = baseScaleX * videoZoom
            val sy = baseScaleY * videoZoom
            val maxOffsetX = w * (sx - 1f) / 2f
            val maxOffsetY = h * (sy - 1f) / 2f

            val matrix = Matrix()
            matrix.setScale(sx, sy, w / 2f, h / 2f)
            matrix.postTranslate(
                -videoPanX.coerceIn(-1f, 1f) * maxOffsetX,
                -videoPanY.coerceIn(-1f, 1f) * maxOffsetY,
            )
            videoTexture.setTransform(matrix)
        }
    }

    private fun updatePreviewViewportAspect() {
        previewOuter.post {
            val maxW = (previewOuter.width - dp(12)).coerceAtLeast(dp(120))
            val maxH = dp(368)
            val (w, h) = fitAspect(targetAspect, maxW, maxH)
            previewViewport.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            previewViewport.requestLayout()
            previewViewport.post { applyVideoPreviewTransform() }
        }
    }

    private fun updateCoverViewportAspect() {
        coverOuter.post {
            val maxW = (coverOuter.width - dp(12)).coerceAtLeast(dp(120))
            val maxH = dp(308)
            val (w, h) = fitAspect(targetAspect, maxW, maxH)
            coverViewport.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            coverViewport.requestLayout()
            coverCropView.targetAspect = targetAspect
        }
    }

    private fun fitAspect(aspect: Float, maxW: Int, maxH: Int): Pair<Int, Int> {
        val safeAspect = aspect.coerceAtLeast(0.05f)
        var width = maxW
        var height = (width / safeAspect).roundToInt()
        if (height > maxH) {
            height = maxH
            width = (height * safeAspect).roundToInt()
        }
        return width.coerceAtLeast(dp(80)) to height.coerceAtLeast(dp(80))
    }

    private fun resetVideoTransform() {
        videoZoom = 1f
        videoPanX = 0f
        videoPanY = 0f
        if (::videoZoomSeek.isInitialized) videoZoomSeek.progress = 0
        if (::gestureView.isInitialized) {
            gestureView.setTransform(1f, 0f, 0f)
        }
        updateVideoTransformInfo()
        applyVideoPreviewTransform()
    }

    private fun resetCoverTransform() {
        coverZoom = 1f
        coverPanX = 0f
        coverPanY = 0f
        if (::coverZoomSeek.isInitialized) coverZoomSeek.progress = 0
        if (::coverCropView.isInitialized) {
            coverCropView.setTransform(1f, 0f, 0f)
        }
        updateCoverTransformInfo()
    }

    private fun updateVideoTransformInfo() {
        if (!::transformInfo.isInitialized) return
        transformInfo.text =
            "缩放 ${"%.2f".format(videoZoom)}× · X ${"%+.0f".format(videoPanX * 100)}% · Y ${"%+.0f".format(videoPanY * 100)}%"
    }

    private fun updateCoverTransformInfo() {
        if (!::coverTransformInfo.isInitialized) return
        coverTransformInfo.text =
            "封面缩放 ${"%.2f".format(coverZoom)}× · X ${"%+.0f".format(coverPanX * 100)}% · Y ${"%+.0f".format(coverPanY * 100)}%"
    }

    private fun updateAspectButtons(selected: Button) {
        aspectButtons.forEach { (button, _) ->
            val active = button === selected
            button.setTextColor(if (active) Color.BLACK else textPrimary)
            button.background = rounded(if (active) accent else panel2, 16f)
        }
    }

    private fun generateMotionPhoto() {
        val cover = coverUri ?: return
        val video = videoUri ?: return

        if (trimEndMs <= trimStartMs + 80L) {
            statusText.text = "切入/切出范围太短，请至少保留 0.1 秒。"
            return
        }

        val videoParams = VideoEditParams(
            startMs = trimStartMs,
            endMs = trimEndMs,
            targetAspect = targetAspect,
            zoom = videoZoom,
            panX = videoPanX,
            panY = videoPanY,
        )
        val coverParams = CoverEditParams(
            targetAspect = targetAspect,
            zoom = coverZoom,
            panX = coverPanX,
            panY = coverPanY,
        )

        player.pause()
        setBusy(true)
        statusText.text =
            "正在用 MediaCodec + OpenGL 导出视频，并按封面预览区域裁剪 JPEG…\n长视频或高分辨率素材需要一些时间。"

        worker.execute {
            try {
                val result = MotionPhotoGenerator.generate(
                    context = applicationContext,
                    coverUri = cover,
                    videoUri = video,
                    videoParams = videoParams,
                    coverParams = coverParams,
                )
                runOnUiThread {
                    resultUri = result.uri
                    setBusy(false)
                    openResultButton.isEnabled = true
                    statusText.text = buildString {
                        appendLine("✓ 生成成功：${result.displayName}")
                        appendLine(
                            "视频：${result.videoWidth}×${result.videoHeight} · ${"%.2f".format(result.durationUs / 1_000_000.0)} s",
                        )
                        appendLine(
                            "视频缩放 ${"%.2f".format(result.zoom)}× · 封面缩放 ${"%.2f".format(result.coverZoom)}×",
                        )
                        append(
                            "已保存至 DCIM/MotionPhotoMaker。请重点测试拖动/缩放预览与最终结果是否一致。",
                        )
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    statusText.text = "生成失败：${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        generateButton.isEnabled = !busy && coverUri != null && videoUri != null
    }

    private fun updateGenerateEnabled() {
        generateButton.isEnabled = coverUri != null && videoUri != null
    }

    private fun openGeneratedPhoto() {
        val uri = resultUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                statusText.text = "文件已经保存，但没有找到可打开 image/jpeg 的相册应用。"
            }
    }

    private data class VideoMeta(
        val durationMs: Long,
        val displayWidth: Int,
        val displayHeight: Int,
    )

    private fun readVideoMeta(uri: Uri): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            val width =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
            val height =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
            val rotation =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            val swap = rotation == 90 || rotation == 270
            VideoMeta(
                durationMs = duration,
                displayWidth = if (swap) height else width,
                displayHeight = if (swap) width else height,
            )
        } finally {
            retriever.release()
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000.0
        val min = (seconds / 60).toInt()
        val sec = seconds - min * 60
        return if (min > 0) "%d:%05.2f".format(min, sec) else "%.2fs".format(sec)
    }

    private fun zoomSeekBar(onChanged: (Float) -> Unit): SeekBar =
        SeekBar(this).apply {
            max = 300
            progress = 0
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        if (fromUser) onChanged(1f + progress / 100f)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                },
            )
        }

    private fun zoomRow(seek: SeekBar): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("1×", 12f))
            addView(
                seek,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(label("4×", 12f))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun sectionTitle(value: String) =
        TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        }

    private fun label(value: String, size: Float) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(textSecondary)
            setLineSpacing(0f, 1.15f)
        }

    private fun card() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(panel, 18f)
        }

    private fun primaryButton(value: String) =
        Button(this).apply {
            text = value
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = rounded(accent, 16f)
        }

    private fun secondaryButton(value: String) =
        Button(this).apply {
            text = value
            textSize = 13f
            isAllCaps = false
            setTextColor(textPrimary)
            background = rounded(panel2, 14f)
        }

    private fun chipButton(value: String) =
        Button(this).apply {
            text = value
            textSize = 12f
            isAllCaps = false
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(textPrimary)
            background = rounded(panel2, 16f)
            minWidth = 0
            minHeight = 0
        }

    private fun rounded(color: Int, radiusDp: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    private fun space(width: Int) =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }

    override fun onDestroy() {
        mainHandler.removeCallbacks(playbackTicker)
        if (::player.isInitialized) {
            player.clearVideoTextureView(videoTexture)
            player.release()
        }
        coverBitmap?.recycle()
        worker.shutdownNow()
        super.onDestroy()
    }
}

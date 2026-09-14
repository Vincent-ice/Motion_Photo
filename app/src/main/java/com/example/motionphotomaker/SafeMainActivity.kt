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

/**
 * Safe launcher/editor.
 *
 * ExoPlayer is deliberately NOT created during Activity startup. It is created
 * only after the user picks a video, so device-specific player init failures
 * cannot kill the app on launch.
 */
class SafeMainActivity : ComponentActivity() {
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

    private var sourceDurationMs = 1L
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
    private var player: ExoPlayer? = null

    private lateinit var previewOuter: FrameLayout
    private lateinit var previewViewport: FrameLayout
    private lateinit var videoTexture: TextureView
    private lateinit var gestureView: EditorGestureView
    private lateinit var timeline: TimelineTrimView
    private lateinit var trimInfo: TextView
    private lateinit var videoInfo: TextView
    private lateinit var transformInfo: TextView
    private lateinit var videoZoomSeek: SeekBar
    private lateinit var playButton: Button

    private lateinit var coverOuter: FrameLayout
    private lateinit var coverViewport: FrameLayout
    private lateinit var coverCropView: CoverCropView
    private lateinit var coverName: TextView
    private lateinit var coverTransformInfo: TextView
    private lateinit var coverZoomSeek: SeekBar

    private lateinit var generateButton: Button
    private lateinit var openResultButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val aspectButtons = mutableListOf<Pair<Button, Float?>>()

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                val pos = p.currentPosition
                timeline.setPlayhead(pos)
                if (pos >= trimEndMs) p.seekTo(trimStartMs)
            }
            mainHandler.postDelayed(this, 50L)
        }
    }

    private val coverPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        coverUri = uri
        resultUri = null
        openResultButton.isEnabled = false
        coverName.text = displayName(uri) ?: "已选择 JPEG 封面"
        loadCover(uri)
        updateGenerateEnabled()
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        videoUri = uri
        resultUri = null
        openResultButton.isEnabled = false
        loadVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(buildUi())
            mainHandler.post(ticker)
        } catch (t: Throwable) {
            val error = TextView(this).apply {
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(32, 48, 32, 48)
                text = "Motion Photo Studio 启动失败\n\n${t.javaClass.simpleName}: ${t.message}"
                setTextIsSelectable(true)
            }
            setContentView(error)
        }
    }

    private fun ensurePlayer(): ExoPlayer? {
        player?.let { return it }
        return try {
            val candidate = ExoPlayer.Builder(this).build()
            candidate.setVideoTextureView(videoTexture)
            candidate.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playButton.text = if (isPlaying) "暂停" else "播放选区"
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    playButton.text = if (candidate.isPlaying) "暂停" else "播放选区"
                }
            })
            player = candidate
            candidate
        } catch (t: Throwable) {
            statusText.text = "播放器初始化失败，但 App 不会退出：${t.javaClass.simpleName}: ${t.message}"
            playButton.isEnabled = false
            null
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(36))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            text = "Motion Photo Studio"
            textSize = 27f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(label("安全启动版 · 可视化视频/封面裁剪 · develop", 13f))

        root.addView(sectionTitle("视频"))
        val videoCard = card()
        previewOuter = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoCard.addView(previewOuter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(370)))

        previewViewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        previewOuter.addView(previewViewport, FrameLayout.LayoutParams(dp(208), dp(370), Gravity.CENTER))

        // TextureView does not support background drawables. Keep the black
        // background on previewViewport instead of calling setBackgroundColor here.
        videoTexture = TextureView(this)
        previewViewport.addView(videoTexture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        gestureView = EditorGestureView(this).apply {
            onTransformChanged = { z, x, y ->
                videoZoom = z
                videoPanX = x
                videoPanY = y
                if (::videoZoomSeek.isInitialized) videoZoomSeek.progress = ((z - 1f) * 100f).roundToInt()
                updateVideoInfo()
                applyVideoTransform()
            }
        }
        previewViewport.addView(gestureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val videoButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }
        playButton = secondaryButton("播放选区").apply {
            isEnabled = false
            setOnClickListener {
                val p = ensurePlayer() ?: return@setOnClickListener
                if (p.isPlaying) p.pause() else {
                    if (p.currentPosition !in trimStartMs..trimEndMs) p.seekTo(trimStartMs)
                    p.play()
                }
            }
        }
        videoButtons.addView(playButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        videoButtons.addView(space(dp(8)))
        videoButtons.addView(secondaryButton("选择视频").apply {
            setOnClickListener {
                videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        videoCard.addView(videoButtons)
        videoInfo = label("尚未选择视频", 12f)
        videoCard.addView(videoInfo)
        videoCard.addView(label("画面支持单指拖动、双指缩放、双击重置。", 12f))
        root.addView(videoCard)

        root.addView(sectionTitle("切入 / 切出"))
        val timelineCard = card()
        timeline = TimelineTrimView(this).apply {
            durationMs = 1L
            setTrim(0L, 1L)
            onTrimChanged = { start, end ->
                trimStartMs = start
                trimEndMs = end
                trimInfo.text = "${formatTime(start)} → ${formatTime(end)} · ${"%.2f".format((end - start) / 1000.0)} s"
                player?.let { p -> if (p.currentPosition !in start..end) p.seekTo(start) }
            }
            onPlayheadChanged = { position, fromUser -> if (fromUser) player?.seekTo(position) }
        }
        timelineCard.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))
        trimInfo = label("拖动两侧手柄裁剪时间", 12f)
        timelineCard.addView(trimInfo)
        root.addView(timelineCard)

        root.addView(sectionTitle("输出比例"))
        val ratioCard = card()
        val ratioScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val ratioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "原始" to null,
            "1:1" to 1f,
            "4:3" to (4f / 3f),
            "3:4" to (3f / 4f),
            "16:9" to (16f / 9f),
            "9:16" to (9f / 16f),
        ).forEach { (title, ratio) ->
            val button = chipButton(title)
            button.setOnClickListener {
                targetAspect = ratio ?: if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else 9f / 16f
                resetVideo()
                resetCover()
                coverCropView.targetAspect = targetAspect
                updateAspectButtons(button)
                updateViewportSizes()
                applyVideoTransform()
            }
            aspectButtons += button to ratio
            ratioRow.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(8) })
        }
        ratioScroll.addView(ratioRow)
        ratioCard.addView(ratioScroll)
        root.addView(ratioCard)

        root.addView(sectionTitle("视频缩放"))
        val videoTransformCard = card()
        videoZoomSeek = zoomSeek { z ->
            videoZoom = z
            gestureView.setTransform(videoZoom, videoPanX, videoPanY)
            updateVideoInfo()
            applyVideoTransform()
        }
        videoTransformCard.addView(zoomRow(videoZoomSeek))
        transformInfo = label("视频：1.00× · X +0% · Y +0%", 12f)
        videoTransformCard.addView(transformInfo)
        videoTransformCard.addView(secondaryButton("重置视频取景").apply { setOnClickListener { resetVideo() } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(videoTransformCard)

        root.addView(sectionTitle("封面裁剪"))
        val coverCard = card()
        coverOuter = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        coverCard.addView(coverOuter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)))
        coverViewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        coverOuter.addView(coverViewport, FrameLayout.LayoutParams(dp(174), dp(310), Gravity.CENTER))
        coverCropView = CoverCropView(this).apply {
            targetAspect = this@SafeMainActivity.targetAspect
            onTransformChanged = { z, x, y ->
                coverZoom = z
                coverPanX = x
                coverPanY = y
                if (::coverZoomSeek.isInitialized) coverZoomSeek.progress = ((z - 1f) * 100f).roundToInt()
                updateCoverInfo()
            }
        }
        coverViewport.addView(coverCropView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        coverCard.addView(secondaryButton("选择 JPEG 封面").apply {
            setOnClickListener {
                coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        coverName = label("尚未选择封面", 12f)
        coverCard.addView(coverName)
        coverZoomSeek = zoomSeek { z ->
            coverZoom = z
            coverCropView.setTransform(coverZoom, coverPanX, coverPanY)
            updateCoverInfo()
        }
        coverCard.addView(zoomRow(coverZoomSeek))
        coverTransformInfo = label("封面：1.00× · X +0% · Y +0%", 12f)
        coverCard.addView(coverTransformInfo)
        root.addView(coverCard)

        generateButton = primaryButton("生成 Motion Photo").apply {
            isEnabled = false
            setOnClickListener { generate() }
        }
        root.addView(generateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(20) })

        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progressBar)

        statusText = label("App 启动时不会再初始化播放器；选择视频后才创建播放器。", 13f)
        statusText.setTextIsSelectable(true)
        root.addView(statusText)

        openResultButton = secondaryButton("在相册中打开").apply {
            isEnabled = false
            setOnClickListener { openResult() }
        }
        root.addView(openResultButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        updateAspectButtons(aspectButtons.last().first)
        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun loadVideo(uri: Uri) {
        val p = ensurePlayer() ?: return
        try {
            p.pause()
            p.clearMediaItems()
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()

            val meta = readVideoMeta(uri)
            sourceDurationMs = meta.durationMs.coerceAtLeast(1L)
            sourceWidth = meta.width
            sourceHeight = meta.height
            targetAspect = if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight.toFloat() else 9f / 16f

            trimStartMs = 0L
            trimEndMs = sourceDurationMs
            timeline.durationMs = sourceDurationMs
            timeline.setTrim(trimStartMs, trimEndMs)
            timeline.setPlayhead(0L)
            trimInfo.text = "${formatTime(0L)} → ${formatTime(sourceDurationMs)} · ${"%.2f".format(sourceDurationMs / 1000.0)} s"
            videoInfo.text = "${displayName(uri) ?: "视频"} · ${sourceWidth}×${sourceHeight} · ${formatTime(sourceDurationMs)}"
            playButton.isEnabled = true

            resetVideo()
            resetCover()
            coverCropView.targetAspect = targetAspect
            updateAspectButtons(aspectButtons.first().first)
            updateViewportSizes()
            applyVideoTransform()
            updateGenerateEnabled()

            worker.execute {
                val thumbs = runCatching { ThumbnailLoader.load(applicationContext, uri, sourceDurationMs, 10) }.getOrDefault(emptyList())
                runOnUiThread { timeline.setThumbnails(thumbs) }
            }
        } catch (t: Throwable) {
            statusText.text = "加载视频失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun loadCover(uri: Uri) {
        worker.execute {
            val bmp = runCatching {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    val maxSide = max(info.size.width, info.size.height)
                    if (maxSide > 2048) {
                        val scale = 2048f / maxSide
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()
            runOnUiThread {
                if (bmp == null) {
                    statusText.text = "封面预览解码失败，请换一张 JPEG。"
                    return@runOnUiThread
                }
                coverBitmap?.recycle()
                coverBitmap = bmp
                coverCropView.setBitmap(bmp)
                coverCropView.targetAspect = targetAspect
                resetCover()
                updateViewportSizes()
            }
        }
    }

    private fun applyVideoTransform() {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        videoTexture.post {
            val w = videoTexture.width.toFloat()
            val h = videoTexture.height.toFloat()
            if (w <= 0f || h <= 0f) return@post
            val sourceAspect = sourceWidth.toFloat() / sourceHeight
            val viewportAspect = w / h
            val baseX = if (sourceAspect > viewportAspect) sourceAspect / viewportAspect else 1f
            val baseY = if (sourceAspect < viewportAspect) viewportAspect / sourceAspect else 1f
            val sx = baseX * videoZoom
            val sy = baseY * videoZoom
            val maxX = w * (sx - 1f) / 2f
            val maxY = h * (sy - 1f) / 2f
            videoTexture.setTransform(Matrix().apply {
                setScale(sx, sy, w / 2f, h / 2f)
                postTranslate(-videoPanX * maxX, -videoPanY * maxY)
            })
        }
    }

    private fun updateViewportSizes() {
        previewOuter.post {
            val (w, h) = fitAspect(targetAspect, previewOuter.width - dp(12), dp(358))
            previewViewport.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            previewViewport.requestLayout()
            previewViewport.post { applyVideoTransform() }
        }
        coverOuter.post {
            val (w, h) = fitAspect(targetAspect, coverOuter.width - dp(12), dp(298))
            coverViewport.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            coverViewport.requestLayout()
            coverCropView.targetAspect = targetAspect
        }
    }

    private fun fitAspect(aspect: Float, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val a = aspect.coerceAtLeast(0.05f)
        var w = maxWidth.coerceAtLeast(dp(100))
        var h = (w / a).roundToInt()
        if (h > maxHeight) {
            h = maxHeight
            w = (h * a).roundToInt()
        }
        return w.coerceAtLeast(dp(80)) to h.coerceAtLeast(dp(80))
    }

    private fun resetVideo() {
        videoZoom = 1f
        videoPanX = 0f
        videoPanY = 0f
        if (::videoZoomSeek.isInitialized) videoZoomSeek.progress = 0
        if (::gestureView.isInitialized) gestureView.setTransform(1f, 0f, 0f)
        updateVideoInfo()
        applyVideoTransform()
    }

    private fun resetCover() {
        coverZoom = 1f
        coverPanX = 0f
        coverPanY = 0f
        if (::coverZoomSeek.isInitialized) coverZoomSeek.progress = 0
        if (::coverCropView.isInitialized) coverCropView.setTransform(1f, 0f, 0f)
        updateCoverInfo()
    }

    private fun updateVideoInfo() {
        if (::transformInfo.isInitialized) {
            transformInfo.text = "视频：${"%.2f".format(videoZoom)}× · X ${"%+.0f".format(videoPanX * 100)}% · Y ${"%+.0f".format(videoPanY * 100)}%"
        }
    }

    private fun updateCoverInfo() {
        if (::coverTransformInfo.isInitialized) {
            coverTransformInfo.text = "封面：${"%.2f".format(coverZoom)}× · X ${"%+.0f".format(coverPanX * 100)}% · Y ${"%+.0f".format(coverPanY * 100)}%"
        }
    }

    private fun generate() {
        val cover = coverUri ?: return
        val video = videoUri ?: return
        if (trimEndMs <= trimStartMs + 80L) {
            statusText.text = "选区过短。"
            return
        }

        player?.pause()
        generateButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        statusText.text = "正在导出…"

        val vp = VideoEditParams(trimStartMs, trimEndMs, targetAspect, videoZoom, videoPanX, videoPanY)
        val cp = CoverEditParams(targetAspect, coverZoom, coverPanX, coverPanY)

        worker.execute {
            try {
                val result = MotionPhotoGenerator.generate(applicationContext, cover, video, vp, cp)
                runOnUiThread {
                    resultUri = result.uri
                    progressBar.visibility = View.GONE
                    generateButton.isEnabled = true
                    openResultButton.isEnabled = true
                    statusText.text = "✓ ${result.displayName}\n${result.videoWidth}×${result.videoHeight} · ${"%.2f".format(result.durationUs / 1_000_000.0)} s"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    updateGenerateEnabled()
                    statusText.text = "生成失败：${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }
    }

    private data class VideoMeta(val durationMs: Long, val width: Int, val height: Int)

    private fun readVideoMeta(uri: Uri): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) VideoMeta(duration, height, width) else VideoMeta(duration, width, height)
        } finally {
            retriever.release()
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    private fun openResult() {
        val uri = resultUri ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { statusText.text = "文件已保存，但没有找到可打开它的相册应用。" }
    }

    private fun updateGenerateEnabled() {
        generateButton.isEnabled = coverUri != null && videoUri != null
    }

    private fun updateAspectButtons(selected: Button) {
        aspectButtons.forEach { (button, _) ->
            val active = button === selected
            button.setTextColor(if (active) Color.BLACK else textPrimary)
            button.background = rounded(if (active) accent else panel2, 16f)
        }
    }

    private fun zoomSeek(onZoom: (Float) -> Unit) = SeekBar(this).apply {
        max = 300
        progress = 0
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onZoom(1f + progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun zoomRow(seek: SeekBar) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label("1×", 12f))
        addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("4×", 12f))
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000.0
        val m = (s / 60).toInt()
        val remain = s - m * 60
        return if (m > 0) "%d:%05.2f".format(m, remain) else "%.2fs".format(remain)
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(textPrimary)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(textSecondary)
        setLineSpacing(0f, 1.15f)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(panel, 18f)
    }

    private fun primaryButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.BLACK)
        background = rounded(accent, 16f)
    }

    private fun secondaryButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(textPrimary)
        background = rounded(panel2, 14f)
    }

    private fun chipButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(textPrimary)
        background = rounded(panel2, 16f)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun space(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(width, 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        runCatching { player?.clearVideoTextureView(videoTexture) }
        runCatching { player?.release() }
        player = null
        coverBitmap?.recycle()
        worker.shutdownNow()
        super.onDestroy()
    }
}

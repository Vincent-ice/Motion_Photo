package com.example.motionphotomaker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@UnstableApi
class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(14, 15, 18)
    private val panel = Color.rgb(29, 31, 36)
    private val panel2 = Color.rgb(42, 45, 52)
    private val textPrimary = Color.rgb(246, 247, 249)
    private val textSecondary = Color.rgb(174, 178, 188)
    private val accent = Color.rgb(255, 196, 46)

    private var coverUri: Uri? = null
    private var videoUri: Uri? = null
    private var resultUri: Uri? = null
    private var sourceDurationMs = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var targetAspect = 9f / 16f
    private var trimStartMs = 0L
    private var trimEndMs = 3000L
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer

    private lateinit var previewOuter: FrameLayout
    private lateinit var previewViewport: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var gestureView: EditorGestureView
    private lateinit var timeline: TimelineTrimView
    private lateinit var coverPreview: ImageView
    private lateinit var coverName: TextView
    private lateinit var videoInfo: TextView
    private lateinit var trimInfo: TextView
    private lateinit var transformInfo: TextView
    private lateinit var playButton: Button
    private lateinit var zoomSeek: SeekBar
    private lateinit var generateButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var openResultButton: Button
    private val aspectButtons = mutableListOf<Pair<Button, Float?>>()

    private val playbackTicker = object : Runnable {
        override fun run() {
            if (::player.isInitialized && ::timeline.isInitialized && player.isPlaying) {
                val pos = player.currentPosition
                timeline.setPlayhead(pos)
                if (pos >= trimEndMs) player.seekTo(trimStartMs)
            }
            mainHandler.postDelayed(this, 50L)
        }
    }

    private val coverPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        coverUri = uri
        coverPreview.setImageURI(uri)
        coverName.text = displayName(uri) ?: "已选择 JPEG 封面"
        resultUri = null
        openResultButton.isEnabled = false
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
        player = ExoPlayer.Builder(this).build()
        setContentView(buildUi())
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "暂停" else "播放选区"
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    sourceWidth = videoSize.width
                    sourceHeight = videoSize.height
                    refreshPreviewEffects()
                }
            }
        })
        mainHandler.post(playbackTicker)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(18), dp(16), dp(34))
        }
        root.addView(TextView(this).apply {
            text = "Motion Photo Studio"
            textSize = 28f
            setTextColor(textPrimary)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(label("可视化时间裁剪 · 比例裁剪 · 缩放取景 · 微信兼容导出", 13f).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        root.addView(sectionTitle("视频编辑"))
        val editorCard = card()
        previewOuter = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        editorCard.addView(previewOuter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))

        previewViewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            clipToPadding = true
        }
        previewOuter.addView(previewViewport, FrameLayout.LayoutParams(dp(236), dp(404), Gravity.CENTER))

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        previewViewport.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        gestureView = EditorGestureView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setTransform(1f, 0f, 0f)
            onTransformChanged = { z, x, y ->
                this@MainActivity.zoom = z
                this@MainActivity.panX = x
                this@MainActivity.panY = y
                if (::zoomSeek.isInitialized) zoomSeek.progress = ((z - 1f) * 100f).roundToInt()
                if (::transformInfo.isInitialized) updateTransformInfo()
                refreshPreviewEffects()
            }
        }
        previewViewport.addView(gestureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
                    if (player.currentPosition !in trimStartMs..trimEndMs) player.seekTo(trimStartMs)
                    player.play()
                }
            }
        }
        playRow.addView(playButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        playRow.addView(space(dp(8)))
        playRow.addView(secondaryButton("选择视频").apply {
            setOnClickListener {
                videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        editorCard.addView(playRow)
        videoInfo = label("尚未选择视频", 12f)
        editorCard.addView(videoInfo)
        root.addView(editorCard)

        root.addView(sectionTitle("切入 / 切出"))
        val timelineCard = card()
        timeline = TimelineTrimView(this).apply {
            durationMs = 1L
            setTrim(0L, 1L)
            onTrimChanged = { start, end ->
                this@MainActivity.trimStartMs = start
                this@MainActivity.trimEndMs = end
                trimInfo.text = "${formatTime(start)}  →  ${formatTime(end)}   ·   ${"%.2f".format((end - start) / 1000.0)} s"
                if (player.currentPosition !in start..end) player.seekTo(start)
            }
            onPlayheadChanged = { position, fromUser ->
                if (fromUser) player.seekTo(position)
            }
        }
        timelineCard.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        trimInfo = label("拖动左右白色手柄设置切入和切出；点击/拖动中间区域可预览。", 12f).apply {
            setPadding(0, dp(8), 0, 0)
        }
        timelineCard.addView(trimInfo)
        root.addView(timelineCard)

        root.addView(sectionTitle("画面比例"))
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
        ).forEach { (name, ratio) ->
            val button = chipButton(name)
            button.setOnClickListener {
                targetAspect = ratio ?: if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else 9f / 16f
                zoom = 1f
                panX = 0f
                panY = 0f
                zoomSeek.progress = 0
                gestureView.setTransform(1f, 0f, 0f)
                updateAspectButtons(button)
                updateViewportAspect()
                updateTransformInfo()
                refreshPreviewEffects()
            }
            aspectButtons += button to ratio
            ratioRow.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                marginEnd = dp(8)
            })
        }
        ratioScroll.addView(ratioRow)
        ratioCard.addView(ratioScroll)
        root.addView(ratioCard)

        root.addView(sectionTitle("缩放与取景"))
        val transformCard = card()
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        zoomRow.addView(label("1×", 12f))
        zoomSeek = SeekBar(this).apply {
            max = 300
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    this@MainActivity.zoom = 1f + progress / 100f
                    gestureView.setTransform(this@MainActivity.zoom, panX, panY)
                    updateTransformInfo()
                    refreshPreviewEffects()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        zoomRow.addView(zoomSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        zoomRow.addView(label("4×", 12f))
        transformCard.addView(zoomRow)
        transformInfo = label("缩放 1.00× · 位置居中", 12f)
        transformCard.addView(transformInfo)
        transformCard.addView(label("预览区域支持双指缩放、单指拖拽取景；三分线辅助构图。导出会采用相同画面。", 12f).apply {
            setPadding(0, dp(5), 0, 0)
        })
        transformCard.addView(secondaryButton("重置取景").apply {
            setOnClickListener {
                this@MainActivity.zoom = 1f
                this@MainActivity.panX = 0f
                this@MainActivity.panY = 0f
                zoomSeek.progress = 0
                gestureView.reset()
                updateTransformInfo()
                refreshPreviewEffects()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(9) })
        root.addView(transformCard)

        root.addView(sectionTitle("静态封面"))
        val coverCard = card()
        coverPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(panel2)
        }
        coverCard.addView(coverPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        coverCard.addView(secondaryButton("选择 JPEG 封面").apply {
            setOnClickListener {
                coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        coverName = label("尚未选择封面", 12f)
        coverCard.addView(coverName)
        coverCard.addView(label("封面会自动裁成最终视频的同一宽高比。根据目前实测，这是微信识别动态照片的关键兼容条件。", 12f))
        root.addView(coverCard)

        generateButton = primaryButton("生成 Motion Photo").apply {
            isEnabled = false
            setOnClickListener { generateMotionPhoto() }
        }
        root.addView(generateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(22) })

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(10) })

        statusText = label("选择视频和 JPEG 封面后即可生成。长视频允许完整保留，不再限制 3 秒。", 13f).apply {
            setPadding(0, dp(10), 0, dp(8))
            setTextIsSelectable(true)
        }
        root.addView(statusText)

        openResultButton = secondaryButton("在相册中打开结果").apply {
            isEnabled = false
            setOnClickListener { openGeneratedPhoto() }
        }
        root.addView(openResultButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

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
        targetAspect = if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else 9f / 16f
        trimStartMs = 0L
        trimEndMs = sourceDurationMs
        zoom = 1f
        panX = 0f
        panY = 0f
        gestureView.setTransform(1f, 0f, 0f)
        zoomSeek.progress = 0

        timeline.durationMs = sourceDurationMs
        timeline.setTrim(trimStartMs, trimEndMs)
        timeline.setPlayhead(trimStartMs)
        trimInfo.text = "${formatTime(trimStartMs)}  →  ${formatTime(trimEndMs)}   ·   ${"%.2f".format((trimEndMs - trimStartMs) / 1000.0)} s"
        videoInfo.text = "${displayName(uri) ?: "视频"}  ·  ${sourceWidth}×${sourceHeight}  ·  ${formatTime(sourceDurationMs)}"

        updateAspectButtons(aspectButtons.first().first)
        updateViewportAspect()
        updateTransformInfo()
        refreshPreviewEffects()
        updateGenerateEnabled()

        worker.execute {
            val thumbs = runCatching { ThumbnailLoader.load(applicationContext, uri, sourceDurationMs, 10) }.getOrDefault(emptyList())
            runOnUiThread { timeline.setThumbnails(thumbs) }
        }
    }

    private fun refreshPreviewEffects() {
        if (sourceWidth <= 0 || sourceHeight <= 0 || !::player.isInitialized) return
        val crop = CropMath.compute(sourceWidth, sourceHeight, targetAspect, zoom, panX, panY)
        runCatching {
            player.setVideoEffects(listOf(Crop(crop.left, crop.right, crop.bottom, crop.top)))
        }
    }

    private fun updateViewportAspect() {
        previewOuter.post {
            val maxW = (previewOuter.width - dp(16)).coerceAtLeast(dp(120))
            val maxH = dp(404)
            var w = maxW
            var h = (w / targetAspect).roundToInt()
            if (h > maxH) {
                h = maxH
                w = (h * targetAspect).roundToInt()
            }
            previewViewport.layoutParams = FrameLayout.LayoutParams(
                w.coerceAtLeast(dp(96)),
                h.coerceAtLeast(dp(96)),
                Gravity.CENTER,
            )
        }
    }

    private fun updateTransformInfo() {
        transformInfo.text = "缩放 ${"%.2f".format(zoom)}× · X ${"%+.0f".format(panX * 100)}% · Y ${"%+.0f".format(panY * 100)}%"
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
        if (trimEndMs <= trimStartMs + 50L) {
            statusText.text = "切入/切出范围太短，请至少保留 0.1 秒。"
            return
        }
        val params = VideoEditParams(
            startMs = trimStartMs,
            endMs = trimEndMs,
            targetAspect = targetAspect,
            zoom = zoom,
            panX = panX,
            panY = panY,
        )
        player.pause()
        setBusy(true)
        statusText.text = "正在导出编辑结果并封装 Motion Photo…\n比例/缩放会重新编码为 H.264 + AAC，长视频可能需要一些时间。"

        worker.execute {
            try {
                val result = MotionPhotoGenerator.generate(applicationContext, cover, video, params)
                runOnUiThread {
                    resultUri = result.uri
                    setBusy(false)
                    openResultButton.isEnabled = true
                    statusText.text = buildString {
                        appendLine("✓ 生成成功：${result.displayName}")
                        appendLine("视频：${result.videoWidth}×${result.videoHeight} · ${"%.2f".format(result.durationUs / 1_000_000.0)} s")
                        appendLine("比例：${"%.3f".format(result.targetAspect)} · 缩放 ${"%.2f".format(result.zoom)}×")
                        append("已保存至 DCIM/MotionPhotoMaker，可直接在微信测试实况发送。")
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
            .onFailure { statusText.text = "文件已经保存，但没有找到可打开 image/jpeg 的相册应用。" }
    }

    private data class VideoMeta(val durationMs: Long, val displayWidth: Int, val displayHeight: Int)

    private fun readVideoMeta(uri: Uri): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val swap = rotation == 90 || rotation == 270
            VideoMeta(duration, if (swap) height else width, if (swap) width else height)
        } finally {
            retriever.release()
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(textPrimary)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(textSecondary)
        setLineSpacing(0f, 1.15f)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(panel, 18f)
    }

    private fun primaryButton(value: String) = Button(this).apply {
        text = value
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.BLACK)
        background = rounded(accent, 16f)
    }

    private fun secondaryButton(value: String) = Button(this).apply {
        text = value
        textSize = 13f
        isAllCaps = false
        setTextColor(textPrimary)
        background = rounded(panel2, 14f)
    }

    private fun chipButton(value: String) = Button(this).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(textPrimary)
        background = rounded(panel2, 16f)
        minWidth = 0
        minHeight = 0
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun space(width: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(playbackTicker)
        if (::playerView.isInitialized) playerView.player = null
        player.release()
        worker.shutdownNow()
        super.onDestroy()
    }
}

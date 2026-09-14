package com.example.motionphotomaker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Lightweight 1:1 video editor used by each grid tile.
 *
 * The editor never exports by itself. It returns VideoEditParams-compatible
 * values to GridMotionPhotoActivity, so every grid cell can keep its own trim,
 * zoom and pan state until the whole grid is generated.
 */
class GridSlotEditorActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SLOT_INDEX = "grid_slot_index"
        const val EXTRA_VIDEO_URI = "grid_video_uri"
        const val EXTRA_START_MS = "grid_start_ms"
        const val EXTRA_END_MS = "grid_end_ms"
        const val EXTRA_ZOOM = "grid_zoom"
        const val EXTRA_PAN_X = "grid_pan_x"
        const val EXTRA_PAN_Y = "grid_pan_y"
    }

    private val bg = Color.rgb(16, 17, 20)
    private val panel = Color.rgb(30, 32, 37)
    private val panel2 = Color.rgb(42, 45, 52)
    private val textPrimary = Color.rgb(245, 246, 248)
    private val textSecondary = Color.rgb(174, 178, 188)
    private val accent = Color.rgb(255, 196, 46)

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var slotIndex = -1
    private lateinit var videoUri: Uri
    private var sourceDurationMs = 1L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var trimStartMs = 0L
    private var trimEndMs = 1L
    private var videoZoom = 1f
    private var videoPanX = 0f
    private var videoPanY = 0f

    private var player: ExoPlayer? = null
    private lateinit var previewViewport: FrameLayout
    private lateinit var videoTexture: TextureView
    private lateinit var gestureView: EditorGestureView
    private lateinit var timeline: TimelineTrimView
    private lateinit var trimInfo: TextView
    private lateinit var transformInfo: TextView
    private lateinit var videoInfo: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var playButton: Button
    private lateinit var statusText: TextView

    private val ticker = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.isPlaying) {
                    val position = p.currentPosition
                    timeline.setPlayhead(position)
                    if (position >= trimEndMs) p.seekTo(trimStartMs)
                }
            }
            mainHandler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
        val uriText = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (slotIndex < 0 || uriText.isNullOrBlank()) {
            finish()
            return
        }
        videoUri = Uri.parse(uriText)
        videoZoom = intent.getFloatExtra(EXTRA_ZOOM, 1f).coerceIn(1f, 4f)
        videoPanX = intent.getFloatExtra(EXTRA_PAN_X, 0f).coerceIn(-1f, 1f)
        videoPanY = intent.getFloatExtra(EXTRA_PAN_Y, 0f).coerceIn(-1f, 1f)

        setContentView(buildUi())
        mainHandler.post(ticker)
        loadVideo()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(34))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            text = "编辑宫格 ${slotIndex + 1}"
            textSize = 26f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(label("独立设置这一个视频的切入 / 切出、缩放与位置 · 固定 1:1 输出", 13f))

        root.addView(sectionTitle("视频预览"))
        val previewOuter = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewViewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            clipToPadding = true
        }
        previewOuter.addView(
            previewViewport,
            FrameLayout.LayoutParams(dp(316), dp(316), Gravity.CENTER),
        )
        videoTexture = TextureView(this)
        previewViewport.addView(
            videoTexture,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        gestureView = EditorGestureView(this).apply {
            onTransformChanged = { zoom, panX, panY ->
                videoZoom = zoom
                videoPanX = panX
                videoPanY = panY
                zoomSeek.progress = ((zoom - 1f) * 100f).roundToInt()
                updateTransformInfo()
            }
        }
        previewViewport.addView(
            gestureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            previewOuter,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(336)),
        )

        val playRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        playButton = secondaryButton("播放选区").apply {
            isEnabled = false
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.isPlaying) {
                    p.pause()
                } else {
                    if (p.currentPosition !in trimStartMs..trimEndMs) p.seekTo(trimStartMs)
                    p.play()
                }
            }
        }
        playRow.addView(playButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        playRow.addView(space(dp(8)))
        playRow.addView(
            secondaryButton("重置取景").apply {
                setOnClickListener {
                    videoZoom = 1f
                    videoPanX = 0f
                    videoPanY = 0f
                    zoomSeek.progress = 0
                    gestureView.setTransform(1f, 0f, 0f)
                    updateTransformInfo()
                }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f),
        )
        root.addView(playRow)
        videoInfo = label("正在读取视频…", 12f)
        root.addView(videoInfo)

        root.addView(sectionTitle("切入 / 切出"))
        timeline = TimelineTrimView(this).apply {
            durationMs = 1L
            setTrim(0L, 1L)
            onTrimChanged = { start, end ->
                trimStartMs = start
                trimEndMs = end
                trimInfo.text = "${formatTime(start)} → ${formatTime(end)} · ${"%.2f".format((end - start) / 1000.0)} s"
                player?.let { p -> if (p.currentPosition !in start..end) p.seekTo(start) }
            }
            onPlayheadChanged = { position, fromUser ->
                if (fromUser) player?.seekTo(position)
            }
        }
        root.addView(
            timeline,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)),
        )
        trimInfo = label("拖动左右手柄设置时间范围；短选区会自动进入精调视图。", 12f)
        root.addView(trimInfo)

        root.addView(sectionTitle("缩放 / 位置"))
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        zoomRow.addView(label("1×", 12f))
        zoomSeek = SeekBar(this).apply {
            max = 300
            progress = ((videoZoom - 1f) * 100f).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    videoZoom = 1f + progress / 100f
                    gestureView.setTransform(videoZoom, videoPanX, videoPanY)
                    updateTransformInfo()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        zoomRow.addView(zoomSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        zoomRow.addView(label("4×", 12f))
        root.addView(zoomRow)
        transformInfo = label("", 12f)
        root.addView(transformInfo)
        root.addView(label("直接在画面上单指拖动、双指缩放；双击恢复 1× 居中。", 12f))

        statusText = label("", 12f)
        root.addView(statusText)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        actionRow.addView(
            secondaryButton("取消").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(0, dp(50), 1f),
        )
        actionRow.addView(space(dp(10)))
        actionRow.addView(
            primaryButton("保存此格设置").apply { setOnClickListener { saveAndFinish() } },
            LinearLayout.LayoutParams(0, dp(50), 1.45f),
        )
        root.addView(actionRow)

        updateTransformInfo()
        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun loadVideo() {
        statusText.text = "正在载入宫格 ${slotIndex + 1} 的视频…"
        val meta = try {
            readVideoMeta(videoUri)
        } catch (t: Throwable) {
            statusText.text = "读取视频失败：${t.message}"
            return
        }
        sourceDurationMs = meta.durationMs.coerceAtLeast(1L)
        sourceWidth = meta.width
        sourceHeight = meta.height

        val requestedStart = intent.getLongExtra(EXTRA_START_MS, 0L)
        val requestedEnd = intent.getLongExtra(EXTRA_END_MS, -1L)
        trimStartMs = requestedStart.coerceIn(0L, (sourceDurationMs - 100L).coerceAtLeast(0L))
        trimEndMs = if (requestedEnd > trimStartMs) {
            requestedEnd.coerceIn((trimStartMs + 100L).coerceAtMost(sourceDurationMs), sourceDurationMs)
        } else {
            sourceDurationMs
        }

        val p = ExoPlayer.Builder(this).build()
        p.setVideoTextureView(videoTexture)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "暂停" else "播放选区"
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                previewViewport.post { layoutVideoTextureBase() }
            }
        })
        player = p
        p.setMediaItem(MediaItem.fromUri(videoUri))
        p.prepare()
        p.seekTo(trimStartMs)

        timeline.durationMs = sourceDurationMs
        timeline.setTrim(trimStartMs, trimEndMs)
        timeline.setPlayhead(trimStartMs)
        trimInfo.text = "${formatTime(trimStartMs)} → ${formatTime(trimEndMs)} · ${"%.2f".format((trimEndMs - trimStartMs) / 1000.0)} s"
        videoInfo.text = "${displayName(videoUri) ?: "视频"} · ${sourceWidth}×${sourceHeight} · ${formatTime(sourceDurationMs)}"
        playButton.isEnabled = true

        gestureView.setTransform(videoZoom, videoPanX, videoPanY)
        zoomSeek.progress = ((videoZoom - 1f) * 100f).roundToInt()
        updateTransformInfo()
        previewViewport.post { layoutVideoTextureBase() }
        statusText.text = "已载入。此页修改只影响第 ${slotIndex + 1} 格。"

        worker.execute {
            val thumbnails = runCatching {
                ThumbnailLoader.load(applicationContext, videoUri, sourceDurationMs, 10)
            }.getOrDefault(emptyList())
            runOnUiThread { timeline.setThumbnails(thumbnails) }
        }
    }

    private fun layoutVideoTextureBase() {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val vw = previewViewport.width
        val vh = previewViewport.height
        if (vw <= 0 || vh <= 0) return

        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val viewportAspect = vw.toFloat() / vh.toFloat()
        val textureWidth: Int
        val textureHeight: Int
        if (sourceAspect >= viewportAspect) {
            textureHeight = vh
            textureWidth = (vh * sourceAspect).roundToInt().coerceAtLeast(vw)
        } else {
            textureWidth = vw
            textureHeight = (vw / sourceAspect).roundToInt().coerceAtLeast(vh)
        }
        videoTexture.layoutParams = FrameLayout.LayoutParams(textureWidth, textureHeight, Gravity.CENTER)
        videoTexture.requestLayout()
        videoTexture.post { gestureView.refreshPreviewTransform() }
    }

    private fun saveAndFinish() {
        if (trimEndMs <= trimStartMs + 80L) {
            statusText.text = "选区过短，请至少保留 0.1 秒。"
            return
        }
        player?.pause()
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_SLOT_INDEX, slotIndex)
                putExtra(EXTRA_START_MS, trimStartMs)
                putExtra(EXTRA_END_MS, trimEndMs)
                putExtra(EXTRA_ZOOM, videoZoom)
                putExtra(EXTRA_PAN_X, videoPanX)
                putExtra(EXTRA_PAN_Y, videoPanY)
            },
        )
        finish()
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
            require(duration > 100L && width > 0 && height > 0) { "视频元数据无效。" }
            if (rotation == 90 || rotation == 270) VideoMeta(duration, height, width)
            else VideoMeta(duration, width, height)
        } finally {
            retriever.release()
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    private fun updateTransformInfo() {
        if (::transformInfo.isInitialized) {
            transformInfo.text = "视频：${"%.2f".format(videoZoom)}× · X ${"%+.0f".format(videoPanX * 100)}% · Y ${"%+.0f".format(videoPanY * 100)}%"
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000.0
        val minutes = (seconds / 60).toInt()
        val rest = seconds - minutes * 60
        return if (minutes > 0) "%d:%05.2f".format(minutes, rest) else "%.2fs".format(rest)
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
        setLineSpacing(0f, 1.18f)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun primaryButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.BLACK)
        background = rounded(accent, 15f)
    }

    private fun secondaryButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = 0
        setTextColor(textPrimary)
        background = rounded(panel2, 14f)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun space(width: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        worker.shutdownNow()
        super.onDestroy()
    }
}

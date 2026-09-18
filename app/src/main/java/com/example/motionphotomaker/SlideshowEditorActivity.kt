package com.example.motionphotomaker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.roundToInt

class SlideshowEditorActivity : ComponentActivity() {
    private val bg = Color.rgb(16, 17, 20)
    private val panel = Color.rgb(30, 32, 37)
    private val panel2 = Color.rgb(42, 45, 52)
    private val textPrimary = Color.rgb(245, 246, 248)
    private val textSecondary = Color.rgb(174, 178, 188)
    private val accent = Color.rgb(255, 196, 46)

    private data class SlideItem(
        val uri: Uri,
        var durationMs: Long = 2_000L,
        var zoom: Float = 1f,
        var panX: Float = 0f,
        var panY: Float = 0f,
    )

    private val slides = mutableListOf<SlideItem>()
    private var selectedIndex = -1
    private var musicUri: Uri? = null
    private var outputWidth = 1080
    private var outputHeight = 1920
    private var fadeEnabled = true
    private var lastOutputUri: Uri? = null
    private var pendingOpenMotionEditor = false

    private val handler = Handler(Looper.getMainLooper())
    private val thumbWorker = Executors.newFixedThreadPool(2)
    private val previewWorker = Executors.newSingleThreadExecutor()
    private var previewFuture: Future<*>? = null
    private var previewBitmap: Bitmap? = null
    private lateinit var exporter: SlideshowExporter
    private var mediaPlayer: MediaPlayer? = null
    private var previewing = false
    private var previewIndex = 0

    private lateinit var previewFrame: FrameLayout
    private lateinit var previewImage: CoverCropView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: SlideAdapter
    private lateinit var durationSeek: SeekBar
    private lateinit var durationText: TextView
    private lateinit var imageZoomSeek: SeekBar
    private lateinit var imageTransformText: TextView
    private lateinit var summaryText: TextView
    private lateinit var musicText: TextView
    private lateinit var previewButton: Button
    private lateinit var exportButton: Button
    private lateinit var exportAndUseButton: Button
    private lateinit var openOutputButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView

    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            stopPreview()
            slides.clear()
            slides += uris.map { SlideItem(it) }
            selectedIndex = 0
            lastOutputUri = null
            adapter.notifyDataSetChanged()
            updateSelectionUi()
            updateSummary()
            updateButtons()
            showSelectedInPreview()
            statusText.text = "已选择 ${slides.size} 张图片。长按缩略图可拖动排序。"
        }

    private val musicPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            musicUri = uri
            musicText.text = displayName(uri) ?: "已选择配乐"
            statusText.text = "已添加配乐；导出时会自动循环或裁切到幻灯片总时长。"
        }

    private val previewRunnable = object : Runnable {
        override fun run() {
            if (!previewing || slides.isEmpty()) return
            previewIndex = (previewIndex + 1) % slides.size
            showPreviewSlide(previewIndex, animate = true)
            handler.postDelayed(this, slides[previewIndex].durationMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exporter = SlideshowExporter(applicationContext)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(34))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            text = "照片幻灯片视频"
            textSize = 27f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(label("多张照片 → H.264 MP4，可配乐后作为 Motion Photo 的视频部分", 13f))

        root.addView(sectionTitle("预览"))
        val previewCard = card()
        previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        previewImage = CoverCropView(this).apply {
            targetAspect = outputWidth.toFloat() / outputHeight.toFloat()
            showGrid = true
            onTransformChanged = { zoom, panX, panY ->
                if (!previewing && selectedIndex in slides.indices) {
                    slides[selectedIndex].zoom = zoom
                    slides[selectedIndex].panX = panX
                    slides[selectedIndex].panY = panY
                    if (::imageZoomSeek.isInitialized) {
                        imageZoomSeek.progress = ((zoom - 1f) * 100f).roundToInt().coerceIn(0, 300)
                    }
                    if (::imageTransformText.isInitialized) updateImageTransformText()
                }
            }
        }
        previewFrame.addView(
            previewImage,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        previewCard.addView(
            previewFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)),
        )
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        previewButton = secondaryButton("播放预览").apply {
            isEnabled = false
            setOnClickListener { if (previewing) stopPreview() else startPreview() }
        }
        previewRow.addView(previewButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        previewRow.addView(space(dp(8)))
        previewRow.addView(
            secondaryButton("选择图片").apply {
                setOnClickListener {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f),
        )
        previewCard.addView(previewRow)
        previewCard.addView(label("编辑状态下：单指拖动图片，双指缩放，双击恢复。每张照片独立保存取景。", 11f))
        root.addView(previewCard)

        root.addView(sectionTitle("图片顺序与时长"))
        val slidesCard = card()
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SlideshowEditorActivity, LinearLayoutManager.HORIZONTAL, false)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = SlideAdapter()
        recycler.adapter = adapter
        slidesCard.addView(
            recycler,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(148)),
        )
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from !in slides.indices || to !in slides.indices) return false
                    Collections.swap(slides, from, to)
                    if (selectedIndex == from) selectedIndex = to
                    else if (selectedIndex == to) selectedIndex = from
                    adapter.notifyItemMoved(from, to)
                    updateSummary()
                    showSelectedInPreview()
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            },
        ).attachToRecyclerView(recycler)

        durationText = label("选择一张图片后调整停留时长", 12f)
        slidesCard.addView(durationText)
        durationSeek = SeekBar(this).apply {
            max = 95
            progress = 15
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || selectedIndex !in slides.indices) return
                    val duration = 500L + progress * 100L
                    slides[selectedIndex].durationMs = duration
                    durationText.text = "第 ${selectedIndex + 1} 张：${"%.1f".format(duration / 1000.0)} 秒"
                    adapter.notifyItemChanged(selectedIndex)
                    updateSummary()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        slidesCard.addView(durationSeek)

        imageTransformText = label("图片取景：1.00× · X +0% · Y +0%", 12f)
        slidesCard.addView(imageTransformText)
        imageZoomSeek = SeekBar(this).apply {
            max = 300
            progress = 0
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || selectedIndex !in slides.indices) return
                    val item = slides[selectedIndex]
                    item.zoom = 1f + progress / 100f
                    previewImage.setTransform(item.zoom, item.panX, item.panY)
                    updateImageTransformText()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        slidesCard.addView(imageZoomSeek)
        slidesCard.addView(
            secondaryButton("重置当前图片取景").apply {
                setOnClickListener {
                    val item = slides.getOrNull(selectedIndex) ?: return@setOnClickListener
                    item.zoom = 1f
                    item.panX = 0f
                    item.panY = 0f
                    imageZoomSeek.progress = 0
                    previewImage.setTransform(1f, 0f, 0f)
                    updateImageTransformText()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(4) },
        )

        summaryText = label("尚未选择图片", 12f)
        slidesCard.addView(summaryText)
        slidesCard.addView(label("长按缩略图左右拖动排序；每张可设置 0.5–10.0 秒，并保存独立缩放/位置。", 11f))
        root.addView(slidesCard)

        root.addView(sectionTitle("配乐"))
        val musicCard = card()
        val musicRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        musicRow.addView(
            secondaryButton("选择本地音乐").apply {
                setOnClickListener { musicPicker.launch(arrayOf("audio/*")) }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f),
        )
        musicRow.addView(space(dp(8)))
        musicRow.addView(
            secondaryButton("移除配乐").apply {
                setOnClickListener {
                    stopPreview()
                    musicUri = null
                    musicText.text = "无配乐（静音视频）"
                }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f),
        )
        musicCard.addView(musicRow)
        musicText = label("无配乐（静音视频）", 12f)
        musicCard.addView(musicText)
        musicCard.addView(label("音乐短于视频时自动循环，长于视频时自动裁到幻灯片结束。", 11f))
        root.addView(musicCard)

        root.addView(sectionTitle("输出"))
        val outputCard = card()
        val ratioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("1:1", 1080, 1080),
            Triple("16:9", 1920, 1080),
            Triple("9:16", 1080, 1920),
        ).forEach { (title, width, height) ->
            val button = chipButton(title)
            button.setOnClickListener {
                outputWidth = width
                outputHeight = height
                for (i in 0 until ratioRow.childCount) {
                    val child = ratioRow.getChildAt(i) as Button
                    val active = child === button
                    child.background = rounded(if (active) accent else panel2, 14f)
                    child.setTextColor(if (active) Color.BLACK else textPrimary)
                }
                updatePreviewAspect()
            }
            if (title == "9:16") {
                button.background = rounded(accent, 14f)
                button.setTextColor(Color.BLACK)
            }
            ratioRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        }
        outputCard.addView(ratioRow)
        outputCard.addView(Switch(this).apply {
            text = "每张图片边缘淡入淡出"
            textSize = 12f
            setTextColor(textPrimary)
            isChecked = true
            setOnCheckedChangeListener { _, checked -> fadeEnabled = checked }
        })
        root.addView(outputCard)

        exportButton = primaryButton("导出 MP4").apply {
            isEnabled = false
            setOnClickListener { beginExport(openMotionEditor = false) }
        }
        root.addView(exportButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })

        exportAndUseButton = secondaryButton("导出并进入单张动态照片编辑器").apply {
            isEnabled = false
            setOnClickListener { beginExport(openMotionEditor = true) }
        }
        root.addView(exportAndUseButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress)
        statusText = label("选择两张或更多照片即可制作幻灯片视频。", 13f).apply { setTextIsSelectable(true) }
        root.addView(statusText)

        openOutputButton = secondaryButton("播放已导出视频").apply {
            isEnabled = false
            setOnClickListener { openOutput() }
        }
        root.addView(openOutputButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })

        updatePreviewAspect()
        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun startPreview() {
        if (slides.isEmpty()) return
        stopPreview()
        previewing = true
        previewIndex = 0
        previewButton.text = "停止预览"
        showPreviewSlide(0, animate = false)
        startPreviewMusic()
        handler.postDelayed(previewRunnable, slides[0].durationMs)
    }

    private fun stopPreview() {
        previewing = false
        handler.removeCallbacks(previewRunnable)
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        if (::previewButton.isInitialized) previewButton.text = "播放预览"
        if (::previewImage.isInitialized) {
            previewImage.showGrid = true
            showSelectedInPreview()
        }
    }

    private fun startPreviewMusic() {
        val uri = musicUri ?: return
        runCatching {
            MediaPlayer().also { player ->
                mediaPlayer = player
                player.setDataSource(this, uri)
                player.isLooping = true
                player.setOnPreparedListener { if (previewing) it.start() }
                player.prepareAsync()
            }
        }.onFailure {
            statusText.text = "配乐预览失败：${it.message}；仍可尝试导出。"
        }
    }

    private fun showPreviewSlide(index: Int, animate: Boolean) {
        val item = slides.getOrNull(index) ?: return
        previewImage.animate().cancel()
        previewImage.alpha = if (animate && fadeEnabled) 0.15f else 1f
        previewImage.showGrid = !previewing
        previewImage.targetAspect = outputWidth.toFloat() / outputHeight.toFloat()
        previewImage.setTransform(item.zoom, item.panX, item.panY)

        val token = "$index:${item.uri}:${System.nanoTime()}"
        previewImage.tag = token
        previewFuture?.cancel(true)

        // Show a cached system thumbnail immediately, then replace it with a
        // high-resolution decode from the original image. loadThumbnail() may
        // return a much smaller cached bitmap than requested on some devices.
        thumbWorker.execute {
            val thumbnail = runCatching {
                contentResolver.loadThumbnail(item.uri, Size(720, 720), null)
            }.getOrNull()
            previewImage.post {
                if (previewImage.tag == token && thumbnail != null) {
                    val old = previewBitmap
                    previewBitmap = null
                    previewImage.setBitmap(thumbnail)
                    if (old != null && old !== thumbnail && !old.isRecycled) old.recycle()
                }
            }
        }

        val frameWidth = previewFrame.width.coerceAtLeast(resources.displayMetrics.widthPixels - dp(56))
        val frameHeight = previewFrame.height.coerceAtLeast(dp(360))
        val requestedLongEdge = (max(frameWidth, frameHeight) * 4).coerceIn(1600, 4096)

        previewFuture = previewWorker.submit {
            val bitmap = runCatching {
                decodePreviewBitmap(item.uri, requestedLongEdge)
            }.getOrNull()
            if (bitmap == null) return@submit

            previewImage.post {
                if (previewImage.tag != token) {
                    bitmap.recycle()
                    return@post
                }
                val old = previewBitmap
                previewBitmap = bitmap
                previewImage.setBitmap(bitmap)
                if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
                if (animate && fadeEnabled) {
                    previewImage.animate().alpha(1f).setDuration(180L).start()
                } else {
                    previewImage.alpha = 1f
                }
            }
        }
    }

    private fun decodePreviewBitmap(uri: Uri, requestedLongEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val sourceWidth = info.size.width.coerceAtLeast(1)
            val sourceHeight = info.size.height.coerceAtLeast(1)
            val sourceLongEdge = max(sourceWidth, sourceHeight)
            if (sourceLongEdge > requestedLongEdge) {
                val scale = requestedLongEdge.toFloat() / sourceLongEdge.toFloat()
                decoder.setTargetSize(
                    (sourceWidth * scale).roundToInt().coerceAtLeast(1),
                    (sourceHeight * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }
    }

    private fun showSelectedInPreview() {
        if (previewing) return
        val index = selectedIndex.takeIf { it in slides.indices } ?: return
        showPreviewSlide(index, animate = false)
    }

    private fun updateSelectionUi() {
        val item = slides.getOrNull(selectedIndex)
        durationSeek.isEnabled = item != null
        imageZoomSeek.isEnabled = item != null
        if (item == null) {
            durationText.text = "选择一张图片后调整停留时长"
            imageTransformText.text = "图片取景：未选择"
            return
        }
        durationSeek.progress = ((item.durationMs - 500L) / 100L).toInt().coerceIn(0, 95)
        imageZoomSeek.progress = ((item.zoom - 1f) * 100f).roundToInt().coerceIn(0, 300)
        durationText.text = "第 ${selectedIndex + 1} 张：${"%.1f".format(item.durationMs / 1000.0)} 秒"
        updateImageTransformText()
    }

    private fun updateImageTransformText() {
        val item = slides.getOrNull(selectedIndex)
        imageTransformText.text = if (item == null) {
            "图片取景：未选择"
        } else {
            "图片取景：${"%.2f".format(item.zoom)}× · X ${"%+.0f".format(item.panX * 100)}% · Y ${"%+.0f".format(item.panY * 100)}%"
        }
    }

    private fun updateSummary() {
        val totalMs = slides.sumOf { it.durationMs }
        summaryText.text = if (slides.isEmpty()) {
            "尚未选择图片"
        } else {
            "${slides.size} 张 · 总时长 ${"%.1f".format(totalMs / 1000.0)} 秒 · ${outputWidth}×${outputHeight}"
        }
    }

    private fun updateButtons() {
        val enabled = slides.size >= 2
        previewButton.isEnabled = slides.isNotEmpty()
        exportButton.isEnabled = enabled
        exportAndUseButton.isEnabled = enabled
    }

    private fun updatePreviewAspect() {
        if (!::previewFrame.isInitialized) return
        previewFrame.post {
            val maxW = (resources.displayMetrics.widthPixels - dp(56)).coerceAtLeast(dp(180))
            val maxH = dp(420)
            var width = maxW
            var height = (width * outputHeight.toFloat() / outputWidth.toFloat()).roundToInt()
            if (height > maxH) {
                height = maxH
                width = (height * outputWidth.toFloat() / outputHeight.toFloat()).roundToInt()
            }
            previewFrame.layoutParams = LinearLayout.LayoutParams(width, height).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            previewImage.targetAspect = outputWidth.toFloat() / outputHeight.toFloat()
            if (!previewing) showSelectedInPreview()
            updateSummary()
        }
    }

    private fun beginExport(openMotionEditor: Boolean) {
        if (slides.size < 2) return
        stopPreview()
        pendingOpenMotionEditor = openMotionEditor
        lastOutputUri = null
        openOutputButton.isEnabled = false
        setExporting(true)
        statusText.text = "正在生成 ${slides.size} 张照片的幻灯片视频…"

        val spec = SlideshowExportSpec(
            frames = slides.map { SlideshowFrame(it.uri, it.durationMs, it.zoom, it.panX, it.panY) },
            musicUri = musicUri,
            width = outputWidth,
            height = outputHeight,
            fadeEnabled = fadeEnabled,
        )
        runCatching {
            exporter.export(
                spec,
                onCompleted = { uri ->
                    lastOutputUri = uri
                    setExporting(false)
                    openOutputButton.isEnabled = true
                    statusText.text = "✓ 已保存 ${displayName(uri) ?: "幻灯片视频"}\nMovies/MotionPhotoMaker · ${slides.size} 张 · ${"%.1f".format(slides.sumOf { it.durationMs } / 1000.0)} 秒"
                    if (pendingOpenMotionEditor) {
                        Toast.makeText(
                            this,
                            "视频已保存。进入单张编辑器后选择刚导出的 SLIDESHOW_*.mp4。",
                            Toast.LENGTH_LONG,
                        ).show()
                        startActivity(Intent(this, AlignedEditorActivity::class.java))
                    }
                    pendingOpenMotionEditor = false
                },
                onError = { error ->
                    setExporting(false)
                    pendingOpenMotionEditor = false
                    statusText.text = "导出失败：${error.javaClass.simpleName}: ${error.message}"
                },
            )
        }.onFailure {
            setExporting(false)
            pendingOpenMotionEditor = false
            statusText.text = "无法开始导出：${it.javaClass.simpleName}: ${it.message}"
        }
    }

    private fun setExporting(exporting: Boolean) {
        progress.visibility = if (exporting) View.VISIBLE else View.GONE
        exportButton.isEnabled = !exporting && slides.size >= 2
        exportAndUseButton.isEnabled = !exporting && slides.size >= 2
        previewButton.isEnabled = !exporting && slides.isNotEmpty()
    }

    private fun openOutput() {
        val uri = lastOutputUri ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { statusText.text = "视频已经保存，但没有找到可播放它的应用。" }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return c.getString(index)
            }
        }
        return null
    }

    override fun onDestroy() {
        stopPreview()
        previewFuture?.cancel(true)
        previewWorker.shutdownNow()
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        previewBitmap = null
        exporter.release()
        thumbWorker.shutdownNow()
        super.onDestroy()
    }

    private inner class SlideAdapter : RecyclerView.Adapter<SlideViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.BLACK)
            }
            val text = TextView(parent.context).apply {
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(textSecondary)
                setPadding(0, dp(3), 0, 0)
            }
            root.addView(image, LinearLayout.LayoutParams(dp(104), dp(104)))
            root.addView(text, LinearLayout.LayoutParams(dp(104), dp(28)))
            return SlideViewHolder(root, image, text)
        }

        override fun getItemCount() = slides.size

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            val item = slides[position]
            holder.itemView.background = rounded(if (position == selectedIndex) accent else panel2, 12f)
            holder.text.setTextColor(if (position == selectedIndex) Color.BLACK else textSecondary)
            holder.text.text = "${position + 1} · ${"%.1f".format(item.durationMs / 1000.0)}s"
            val tag = item.uri.toString()
            holder.image.tag = tag
            holder.image.setImageDrawable(null)
            thumbWorker.execute {
                val bitmap = runCatching { contentResolver.loadThumbnail(item.uri, Size(220, 220), null) }.getOrNull()
                holder.image.post {
                    if (holder.image.tag == tag && bitmap != null) holder.image.setImageBitmap(bitmap)
                }
            }
            holder.itemView.setOnClickListener {
                stopPreview()
                val old = selectedIndex
                selectedIndex = holder.bindingAdapterPosition
                if (old >= 0) notifyItemChanged(old)
                if (selectedIndex >= 0) notifyItemChanged(selectedIndex)
                updateSelectionUi()
                showSelectedInPreview()
            }
        }
    }

    private class SlideViewHolder(
        itemView: View,
        val image: ImageView,
        val text: TextView,
    ) : RecyclerView.ViewHolder(itemView)

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(panel, 18f)
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(textSecondary)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(textPrimary)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun primaryButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.BLACK)
        background = rounded(accent, 15f)
    }

    private fun secondaryButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(textPrimary)
        background = rounded(panel2, 15f)
    }

    private fun chipButton(value: String) = secondaryButton(value).apply {
        textSize = 12f
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun space(width: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

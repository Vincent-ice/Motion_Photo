package com.example.motionphotomaker

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class GridMotionPhotoActivity : ComponentActivity() {
    private val bg = Color.rgb(16, 17, 20)
    private val panel = Color.rgb(30, 32, 37)
    private val panel2 = Color.rgb(42, 45, 52)
    private val textPrimary = Color.rgb(245, 246, 248)
    private val textSecondary = Color.rgb(174, 178, 188)
    private val accent = Color.rgb(255, 196, 46)

    private var sourceImageUri: Uri? = null
    private var previewBitmap: Bitmap? = null
    private var gridSpec = GridComposer.layoutFor(9)
    private var videoUris = MutableList<Uri?>(gridSpec.count) { null }
    private var slotEdits = MutableList<VideoEditParams?>(gridSpec.count) { null }
    private var pendingVideoSlot = -1
    private val resultUris = mutableListOf<Uri>()
    private val previewTiles = mutableListOf<Bitmap>()
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var gridContainer: GridLayout
    private lateinit var imageName: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var generateButton: Button
    private lateinit var shareButton: Button

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri ?: return@registerForActivityResult
            sourceImageUri = uri
            resultUris.clear()
            if (::shareButton.isInitialized) shareButton.isEnabled = false
            if (::imageName.isInitialized) imageName.text = displayName(uri) ?: "已选择图片"
            loadPreview(uri)
        }

    private val singleVideoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val slot = pendingVideoSlot
            pendingVideoSlot = -1
            if (uri == null || slot !in videoUris.indices) return@registerForActivityResult
            videoUris[slot] = uri
            slotEdits[slot] = null
            invalidateResults()
            rebuildGrid()
            updateGenerateState()
            launchSlotEditor(slot)
        }

    private val multiVideoPicker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            for (i in videoUris.indices) {
                videoUris[i] = uris.getOrNull(i)
                slotEdits[i] = null
            }
            invalidateResults()
            rebuildGrid()
            updateGenerateState()
            statusText.text = if (uris.size >= gridSpec.count) {
                "已按左上→右下顺序绑定 ${gridSpec.count} 个视频。点击每格“编辑”可独立设置时间与取景。"
            } else {
                "已绑定 ${uris.size}/${gridSpec.count} 个视频，其余格子请单独选择。"
            }
        }

    private val slotEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val slot = data.getIntExtra(GridSlotEditorActivity.EXTRA_SLOT_INDEX, -1)
            if (slot !in videoUris.indices || videoUris[slot] == null) return@registerForActivityResult

            val start = data.getLongExtra(GridSlotEditorActivity.EXTRA_START_MS, 0L)
            val end = data.getLongExtra(GridSlotEditorActivity.EXTRA_END_MS, 1L)
            val zoom = data.getFloatExtra(GridSlotEditorActivity.EXTRA_ZOOM, 1f)
            val panX = data.getFloatExtra(GridSlotEditorActivity.EXTRA_PAN_X, 0f)
            val panY = data.getFloatExtra(GridSlotEditorActivity.EXTRA_PAN_Y, 0f)
            slotEdits[slot] = VideoEditParams(
                startMs = start,
                endMs = end,
                targetAspect = 1f,
                zoom = zoom,
                panX = panX,
                panY = panY,
            )
            invalidateResults()
            rebuildGrid()
            statusText.text = "已保存第 ${slot + 1} 格设置：${formatShortTime(start)} → ${formatShortTime(end)} · ${"%.2f".format(zoom)}×"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        rebuildGrid()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(36))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            text = "宫格动态拼图"
            textSize = 27f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(label("一张图片拆成多个 1:1 Motion Photo · 每格视频独立编辑", 13f))

        root.addView(sectionTitle("1. 选择宫格"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(4, 6, 9).forEach { count ->
            val modeButton = secondaryButton("${count} 宫格")
            if (count == gridSpec.count) {
                modeButton.setTextColor(Color.BLACK)
                modeButton.background = rounded(accent, 14f)
            }
            modeButton.setOnClickListener {
                setGridCount(count)
                for (i in 0 until modeRow.childCount) {
                    val button = modeRow.getChildAt(i) as Button
                    val active = button === modeButton
                    button.setTextColor(if (active) Color.BLACK else textPrimary)
                    button.background = rounded(if (active) accent else panel2, 14f)
                }
            }
            modeRow.addView(
                modeButton,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = if (count != 9) dp(8) else 0
                },
            )
        }
        root.addView(modeRow)
        root.addView(label("4 宫格 = 2×2；6 宫格 = 3×2；9 宫格 = 3×3。每一格固定输出 1:1。", 12f))

        root.addView(sectionTitle("2. 选择拼图原图"))
        val pickImageButton = secondaryButton("选择 JPEG / PNG 图片")
        pickImageButton.setOnClickListener {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        root.addView(pickImageButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        imageName = label("尚未选择图片", 12f)
        root.addView(imageName)
        root.addView(label("原图会先按整组比例居中裁剪，再无缝切分；4/9 宫格整体为 1:1，6 宫格整体为 3:2。", 12f))

        root.addView(sectionTitle("3. 绑定并编辑每格视频"))
        val batchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val batchButton = secondaryButton("一次选择全部视频")
        batchButton.setOnClickListener {
            multiVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        batchRow.addView(batchButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        batchRow.addView(space(dp(8)))
        val clearButton = secondaryButton("清空视频")
        clearButton.setOnClickListener {
            videoUris = MutableList(gridSpec.count) { null }
            slotEdits = MutableList(gridSpec.count) { null }
            invalidateResults()
            rebuildGrid()
            updateGenerateState()
        }
        batchRow.addView(clearButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(batchRow)

        gridContainer = GridLayout(this).apply {
            setPadding(0, dp(10), 0, dp(4))
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        root.addView(gridContainer)
        root.addView(label("每格可以独立设置切入 / 切出、1×–4× 缩放和画面位置。未编辑的格子默认使用完整视频并居中裁成 1:1。", 12f))

        generateButton = primaryButton("生成整组 Motion Photo")
        generateButton.isEnabled = false
        generateButton.setOnClickListener { generateGrid() }
        root.addView(
            generateButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(20) },
        )

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = gridSpec.count
            progress = 0
            visibility = View.GONE
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(12) },
        )

        statusText = label("选择原图并为全部格子绑定视频后即可生成。", 13f)
        statusText.setTextIsSelectable(true)
        root.addView(statusText)

        shareButton = secondaryButton("分享整组到微信")
        shareButton.isEnabled = false
        shareButton.setOnClickListener { shareResultsToWeChat() }
        root.addView(
            shareButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) },
        )
        root.addView(label("若微信没有直接进入朋友圈，请在朋友圈相册选择界面按 1→N 顺序选中刚生成的这一组。", 12f))

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun setGridCount(count: Int) {
        val oldVideos = videoUris
        val oldEdits = slotEdits
        gridSpec = GridComposer.layoutFor(count)
        videoUris = MutableList(gridSpec.count) { index -> oldVideos.getOrNull(index) }
        slotEdits = MutableList(gridSpec.count) { index -> oldEdits.getOrNull(index) }
        invalidateResults()
        if (::progressBar.isInitialized) progressBar.max = gridSpec.count
        rebuildGrid()
        updateGenerateState()
    }

    private fun loadPreview(uri: Uri) {
        statusText.text = "正在解析原图…"
        worker.execute {
            val bitmap = runCatching { GridComposer.decodeImage(applicationContext, uri, 1600) }.getOrNull()
            runOnUiThread {
                if (bitmap == null) {
                    statusText.text = "图片解码失败，请换一张 JPEG / PNG。"
                    return@runOnUiThread
                }
                previewBitmap?.recycle()
                previewBitmap = bitmap
                rebuildGrid()
                updateGenerateState()
                statusText.text = "原图已载入。现在为 ${gridSpec.count} 个格子绑定并编辑视频。"
            }
        }
    }

    private fun rebuildGrid() {
        if (!::gridContainer.isInitialized) return
        clearPreviewTiles()
        gridContainer.removeAllViews()
        gridContainer.columnCount = gridSpec.columns
        gridContainer.rowCount = gridSpec.rows

        val screenWidth = resources.displayMetrics.widthPixels
        val tileWidth = ((screenWidth - dp(44) - dp(8) * (gridSpec.columns - 1)) / gridSpec.columns)
            .coerceAtLeast(dp(86))
        val source = previewBitmap

        repeat(gridSpec.count) { index ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = rounded(panel, 14f)
            }

            card.addView(TextView(this).apply {
                text = "#${index + 1}"
                textSize = 11f
                setTextColor(textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), 0, 0, dp(3))
            })

            val imageView = ImageView(this).apply {
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "宫格 ${index + 1}"
            }
            if (source != null) {
                runCatching { GridComposer.createTile(source, gridSpec, index) }
                    .getOrNull()
                    ?.let { tile ->
                        previewTiles += tile
                        imageView.setImageBitmap(tile)
                    }
            }
            card.addView(imageView, LinearLayout.LayoutParams(tileWidth - dp(8), tileWidth - dp(8)))

            val bound = videoUris.getOrNull(index) != null
            val pickButton = secondaryButton(if (bound) "更换视频" else "选择视频")
            pickButton.setOnClickListener {
                pendingVideoSlot = index
                singleVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
            card.addView(
                pickButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(4) },
            )

            val editButton = secondaryButton(if (slotEdits.getOrNull(index) != null) "✓ 已编辑" else "编辑")
            editButton.isEnabled = bound
            editButton.alpha = if (bound) 1f else 0.45f
            editButton.setOnClickListener { launchSlotEditor(index) }
            card.addView(
                editButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(4) },
            )

            val summary = when {
                !bound -> "未绑定"
                slotEdits.getOrNull(index) == null -> "完整时长 · 1.00×"
                else -> {
                    val p = slotEdits[index]!!
                    "${formatShortTime(p.startMs)}–${formatShortTime(p.endMs)} · ${"%.2f".format(p.zoom)}×"
                }
            }
            card.addView(label(summary, 10f).apply { maxLines = 2 })

            val row = index / gridSpec.columns
            val col = index % gridSpec.columns
            gridContainer.addView(
                card,
                GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col)).apply {
                    width = tileWidth
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    setMargins(
                        if (col == 0) 0 else dp(4),
                        if (row == 0) 0 else dp(4),
                        if (col == gridSpec.columns - 1) 0 else dp(4),
                        if (row == gridSpec.rows - 1) 0 else dp(4),
                    )
                },
            )
        }
    }

    private fun launchSlotEditor(index: Int) {
        val uri = videoUris.getOrNull(index) ?: return
        val saved = slotEdits.getOrNull(index)
        val intent = Intent(this, GridSlotEditorActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(GridSlotEditorActivity.EXTRA_SLOT_INDEX, index)
            putExtra(GridSlotEditorActivity.EXTRA_VIDEO_URI, uri.toString())
            if (saved != null) {
                putExtra(GridSlotEditorActivity.EXTRA_START_MS, saved.startMs)
                putExtra(GridSlotEditorActivity.EXTRA_END_MS, saved.endMs)
                putExtra(GridSlotEditorActivity.EXTRA_ZOOM, saved.zoom)
                putExtra(GridSlotEditorActivity.EXTRA_PAN_X, saved.panX)
                putExtra(GridSlotEditorActivity.EXTRA_PAN_Y, saved.panY)
            }
        }
        slotEditorLauncher.launch(intent)
    }

    private fun generateGrid() {
        val sourceUri = sourceImageUri
        if (sourceUri == null) {
            statusText.text = "请先选择拼图原图。"
            return
        }
        val videos = videoUris.toList()
        val edits = slotEdits.toList()
        if (videos.any { it == null }) {
            val missing = videos.mapIndexedNotNull { index, uri -> if (uri == null) index + 1 else null }
            statusText.text = "还有格子未绑定视频：${missing.joinToString(", ")}"
            return
        }

        generateButton.isEnabled = false
        shareButton.isEnabled = false
        progressBar.max = gridSpec.count
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        statusText.text = "正在准备 ${gridSpec.count} 宫格…"
        resultUris.clear()

        val spec = gridSpec
        worker.execute {
            val created = mutableListOf<Uri>()
            var source: Bitmap? = null
            try {
                source = GridComposer.decodeImage(applicationContext, sourceUri, 3072)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val relativePath = Environment.DIRECTORY_DCIM + "/MotionPhotoMaker/Grid_$timestamp"

                for (index in 0 until spec.count) {
                    val videoUri = videos[index] ?: error("第 ${index + 1} 格没有视频。")
                    val tile = GridComposer.createTile(source, spec, index)
                    val jpeg = try {
                        GridComposer.encodeTileAsJpeg(tile)
                    } finally {
                        tile.recycle()
                    }
                    val durationMs = readVideoDurationMs(videoUri)
                    require(durationMs > 100L) { "第 ${index + 1} 个视频无法读取有效时长。" }
                    val params = normalizeParams(edits[index], durationMs)
                    val displayName = "GRID_${timestamp}_${String.format(Locale.US, "%02d", index + 1)}_MP.jpg"
                    val result = MotionPhotoGenerator.generatePreparedJpeg(
                        context = applicationContext,
                        coverJpeg = jpeg,
                        videoUri = videoUri,
                        videoParams = params,
                        displayName = displayName,
                        relativePath = relativePath,
                    )
                    created += result.uri
                    runOnUiThread {
                        progressBar.progress = index + 1
                        statusText.text = "正在生成 ${index + 1}/${spec.count} · $displayName"
                    }
                }

                runOnUiThread {
                    resultUris.clear()
                    resultUris.addAll(created)
                    progressBar.visibility = View.GONE
                    generateButton.isEnabled = true
                    shareButton.isEnabled = true
                    statusText.text =
                        "✓ 已生成 ${created.size} 张动态宫格\n保存目录：DCIM/MotionPhotoMaker/Grid_$timestamp\n每格已应用自己的时间与取景参数。"
                }
            } catch (t: Throwable) {
                created.forEach { uri -> runCatching { contentResolver.delete(uri, null, null) } }
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    updateGenerateState()
                    shareButton.isEnabled = false
                    statusText.text = "宫格生成失败：${t.javaClass.simpleName}: ${t.message}"
                }
            } finally {
                source?.recycle()
            }
        }
    }

    private fun normalizeParams(saved: VideoEditParams?, durationMs: Long): VideoEditParams {
        if (saved == null) {
            return VideoEditParams(0L, durationMs, 1f, 1f, 0f, 0f)
        }
        val minGap = 100L
        val start = saved.startMs.coerceIn(0L, (durationMs - minGap).coerceAtLeast(0L))
        val end = saved.endMs.coerceIn((start + minGap).coerceAtMost(durationMs), durationMs)
        return saved.copy(
            startMs = start,
            endMs = end,
            targetAspect = 1f,
            zoom = saved.zoom.coerceIn(1f, 4f),
            panX = saved.panX.coerceIn(-1f, 1f),
            panY = saved.panY.coerceIn(-1f, 1f),
        )
    }

    private fun shareResultsToWeChat() {
        if (resultUris.isEmpty()) return
        val streams = ArrayList<Uri>().apply { addAll(resultUris) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.tencent.mm")
            clipData = ClipData.newUri(contentResolver, "Motion Photo grid", streams.first()).apply {
                for (i in 1 until streams.size) addItem(ClipData.Item(streams[i]))
            }
        }
        runCatching { startActivity(intent) }.onFailure {
            intent.setPackage(null)
            runCatching { startActivity(Intent.createChooser(intent, "分享宫格动态照片")) }
                .onFailure { error -> statusText.text = "无法打开分享界面：${error.message}" }
        }
    }

    private fun readVideoDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
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

    private fun invalidateResults() {
        resultUris.clear()
        if (::shareButton.isInitialized) shareButton.isEnabled = false
    }

    private fun updateGenerateState() {
        if (::generateButton.isInitialized) {
            generateButton.isEnabled = sourceImageUri != null && videoUris.all { it != null }
        }
    }

    private fun formatShortTime(ms: Long): String {
        val seconds = ms / 1000.0
        val minutes = (seconds / 60).toInt()
        val rest = seconds - minutes * 60
        return if (minutes > 0) "%d:%04.1f".format(minutes, rest) else "%.1fs".format(rest)
    }

    private fun clearPreviewTiles() {
        previewTiles.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        previewTiles.clear()
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
        background = rounded(accent, 16f)
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
        clearPreviewTiles()
        previewBitmap?.recycle()
        previewBitmap = null
        worker.shutdownNow()
        super.onDestroy()
    }
}

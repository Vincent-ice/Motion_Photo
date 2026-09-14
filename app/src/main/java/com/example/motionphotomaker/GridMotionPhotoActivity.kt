package com.example.motionphotomaker

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
import android.view.Gravity
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
    private var layout = GridComposer.layoutFor(9)
    private var videoUris = MutableList<Uri?>(layout.count) { null }
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
            resultUris.clear()
            shareButton.isEnabled = false
            rebuildGrid()
            updateGenerateState()
        }

    private val multiVideoPicker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            for (i in videoUris.indices) {
                videoUris[i] = uris.getOrNull(i)
            }
            resultUris.clear()
            shareButton.isEnabled = false
            rebuildGrid()
            updateGenerateState()
            statusText.text = if (uris.size >= layout.count) {
                "已按左上→右下顺序绑定 ${layout.count} 个视频。"
            } else {
                "已绑定 ${uris.size}/${layout.count} 个视频，其余格子请单独选择。"
            }
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
        root.addView(label("一张图片拆成多个 1:1 Motion Photo · 左上到右下编号", 13f))

        root.addView(sectionTitle("1. 选择宫格"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(4, 6, 9).forEach { count ->
            modeRow.addView(
                secondaryButton("${count} 宫格").apply {
                    if (count == layout.count) setTextColor(Color.BLACK).also {
                        background = rounded(accent, 14f)
                    }
                    setOnClickListener {
                        setGridCount(count)
                        for (i in 0 until modeRow.childCount) {
                            val b = modeRow.getChildAt(i) as Button
                            val active = b === this
                            b.setTextColor(if (active) Color.BLACK else textPrimary)
                            b.background = rounded(if (active) accent else panel2, 14f)
                        }
                    }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = if (count != 9) dp(8) else 0
                },
            )
        }
        root.addView(modeRow)
        root.addView(label("4 宫格 = 2×2；6 宫格 = 3×2；9 宫格 = 3×3。每一格固定输出 1:1。", 12f))

        root.addView(sectionTitle("2. 选择拼图原图"))
        root.addView(
            secondaryButton("选择 JPEG / PNG 图片").apply {
                setOnClickListener {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
        )
        imageName = label("尚未选择图片", 12f)
        root.addView(imageName)
        root.addView(label("原图会先按整组比例居中裁剪，再无缝切分；4/9 宫格整体为 1:1，6 宫格整体为 3:2。", 12f))

        root.addView(sectionTitle("3. 为每一格绑定视频"))
        val batchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        batchRow.addView(
            secondaryButton("一次选择全部视频").apply {
                setOnClickListener {
                    multiVideoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f),
        )
        batchRow.addView(space(dp(8)))
        batchRow.addView(
            secondaryButton("清空视频").apply {
                setOnClickListener {
                    videoUris = MutableList(layout.count) { null }
                    resultUris.clear()
                    shareButton.isEnabled = false
                    rebuildGrid()
                    updateGenerateState()
                }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f),
        )
        root.addView(batchRow)

        gridContainer = GridLayout(this).apply {
            setPadding(0, dp(10), 0, dp(4))
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        root.addView(gridContainer)

        root.addView(label("点击任意格子的“选择视频”可单独替换。视频会自动中心裁成 1:1，并使用完整时长。", 12f))

        generateButton = primaryButton("生成整组 Motion Photo").apply {
            isEnabled = false
            setOnClickListener { generateGrid() }
        }
        root.addView(
            generateButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                topMargin = dp(20)
            },
        )

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = layout.count
            progress = 0
            visibility = View.GONE
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
                topMargin = dp(12)
            },
        )

        statusText = label("选择原图并为全部格子绑定视频后即可生成。", 13f)
        statusText.setTextIsSelectable(true)
        root.addView(statusText)

        shareButton = secondaryButton("分享整组到微信").apply {
            isEnabled = false
            setOnClickListener { shareResultsToWeChat() }
        }
        root.addView(
            shareButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(10)
            },
        )
        root.addView(label("若微信没有直接进入朋友圈，请在朋友圈相册选择界面按 1→N 顺序选中刚生成的这一组。", 12f))

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun setGridCount(count: Int) {
        val old = videoUris
        layout = GridComposer.layoutFor(count)
        videoUris = MutableList(layout.count) { index -> old.getOrNull(index) }
        resultUris.clear()
        if (::shareButton.isInitialized) shareButton.isEnabled = false
        if (::progressBar.isInitialized) progressBar.max = layout.count
        rebuildGrid()
        updateGenerateState()
    }

    private fun loadPreview(uri: Uri) {
        statusText.text = "正在解析原图…"
        worker.execute {
            val bitmap = runCatching {
                GridComposer.decodeImage(applicationContext, uri, 1600)
            }.getOrNull()
            runOnUiThread {
                if (bitmap == null) {
                    statusText.text = "图片解码失败，请换一张 JPEG / PNG。"
                    return@runOnUiThread
                }
                previewBitmap?.recycle()
                previewBitmap = bitmap
                rebuildGrid()
                updateGenerateState()
                statusText.text = "原图已载入。现在为 ${layout.count} 个格子绑定视频。"
            }
        }
    }

    private fun rebuildGrid() {
        if (!::gridContainer.isInitialized) return
        clearPreviewTiles()
        gridContainer.removeAllViews()
        gridContainer.columnCount = layout.columns
        gridContainer.rowCount = layout.rows

        val screenWidth = resources.displayMetrics.widthPixels
        val tileWidth = ((screenWidth - dp(44) - dp(8) * (layout.columns - 1)) / layout.columns)
            .coerceAtLeast(dp(86))
        val source = previewBitmap

        repeat(layout.count) { index ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = rounded(panel, 14f)
            }

            val imageView = ImageView(this).apply {
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "宫格 ${index + 1}"
            }
            if (source != null) {
                runCatching { GridComposer.createTile(source, layout, index) }
                    .getOrNull()
                    ?.let { tile ->
                        previewTiles += tile
                        imageView.setImageBitmap(tile)
                    }
            }
            card.addView(imageView, LinearLayout.LayoutParams(tileWidth - dp(8), tileWidth - dp(8)))

            val bound = videoUris.getOrNull(index) != null
            card.addView(
                secondaryButton(if (bound) "✓ ${index + 1} · 已绑定" else "${index + 1} · 选择视频").apply {
                    setOnClickListener {
                        pendingVideoSlot = index
                        singleVideoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                    topMargin = dp(4)
                },
            )

            val row = index / layout.columns
            val col = index % layout.columns
            gridContainer.addView(
                card,
                GridLayout.LayoutParams(
                    GridLayout.spec(row),
                    GridLayout.spec(col),
                ).apply {
                    width = tileWidth
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    setMargins(
                        if (col == 0) 0 else dp(4),
                        if (row == 0) 0 else dp(4),
                        if (col == layout.columns - 1) 0 else dp(4),
                        if (row == layout.rows - 1) 0 else dp(4),
                    )
                },
            )
        }
    }

    private fun generateGrid() {
        val sourceUri = sourceImageUri
        if (sourceUri == null) {
            statusText.text = "请先选择拼图原图。"
            return
        }
        val videos = videoUris.toList()
        if (videos.any { it == null }) {
            val missing = videos.mapIndexedNotNull { index, uri -> if (uri == null) index + 1 else null }
            statusText.text = "还有格子未绑定视频：${missing.joinToString(", ")}"
            return
        }

        generateButton.isEnabled = false
        shareButton.isEnabled = false
        progressBar.max = layout.count
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        statusText.text = "正在准备 ${layout.count} 宫格…"
        resultUris.clear()

        val currentLayout = layout
        worker.execute {
            val created = mutableListOf<Uri>()
            var source: Bitmap? = null
            try {
                source = GridComposer.decodeImage(applicationContext, sourceUri, 3072)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val relativePath = Environment.DIRECTORY_DCIM + "/MotionPhotoMaker/Grid_$timestamp"

                for (index in 0 until currentLayout.count) {
                    val videoUri = videos[index] ?: error("第 ${index + 1} 格没有视频。")
                    val tile = GridComposer.createTile(source, currentLayout, index)
                    val jpeg = try {
                        GridComposer.encodeTileAsJpeg(tile)
                    } finally {
                        tile.recycle()
                    }

                    val durationMs = readVideoDurationMs(videoUri)
                    require(durationMs > 100L) { "第 ${index + 1} 个视频无法读取有效时长。" }
                    val params = VideoEditParams(
                        startMs = 0L,
                        endMs = durationMs,
                        targetAspect = 1f,
                        zoom = 1f,
                        panX = 0f,
                        panY = 0f,
                    )
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
                        statusText.text = "正在生成 ${index + 1}/${currentLayout.count} · ${displayName}"
                    }
                }

                runOnUiThread {
                    resultUris.clear()
                    resultUris.addAll(created)
                    progressBar.visibility = View.GONE
                    generateButton.isEnabled = true
                    shareButton.isEnabled = true
                    statusText.text =
                        "✓ 已生成 ${created.size} 张动态宫格\n保存目录：DCIM/MotionPhotoMaker/Grid_$timestamp\n按 1→${created.size} 顺序选择即可还原整幅拼图。"
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
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
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

    private fun updateGenerateState() {
        if (::generateButton.isInitialized) {
            generateButton.isEnabled = sourceImageUri != null && videoUris.all { it != null }
        }
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

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        clearPreviewTiles()
        previewBitmap?.recycle()
        previewBitmap = null
        worker.shutdownNow()
        super.onDestroy()
    }
}

package com.example.motionphotomaker

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var coverUri: Uri? = null
    private var videoUri: Uri? = null
    private var resultUri: Uri? = null
    private var sourceVideoDurationMs: Long? = null
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var coverPreview: ImageView
    private lateinit var videoPreview: VideoView
    private lateinit var coverNameText: TextView
    private lateinit var videoNameText: TextView
    private lateinit var sourceDurationText: TextView
    private lateinit var trimStartInput: EditText
    private lateinit var trimDurationInput: EditText
    private lateinit var statusText: TextView
    private lateinit var generateButton: Button
    private lateinit var openResultButton: Button
    private lateinit var progressBar: ProgressBar

    private val coverPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        coverUri = uri
        coverPreview.setImageURI(uri)
        coverNameText.text = displayName(uri) ?: uri.toString()
        resultUri = null
        openResultButton.isEnabled = false
        statusText.text = "JPEG 封面已选择。生成时会自动中心裁剪到视频宽高比。"
        updateGenerateEnabled()
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        videoUri = uri
        videoNameText.text = displayName(uri) ?: uri.toString()
        videoPreview.setVideoURI(uri)
        videoPreview.setMediaController(MediaController(this).apply { setAnchorView(videoPreview) })
        videoPreview.seekTo(1)
        sourceVideoDurationMs = readDurationMs(uri)
        val durationMs = sourceVideoDurationMs
        sourceDurationText.text = if (durationMs != null && durationMs > 0) {
            "原视频时长：${String.format("%.3f", durationMs / 1000.0)} s"
        } else {
            "原视频时长：未知"
        }
        trimStartInput.setText("0.0")
        if (durationMs != null && durationMs > 0) {
            trimDurationInput.setText(String.format("%.3f", minOf(3_000L, durationMs) / 1000.0))
        }
        resultUri = null
        openResultButton.isEnabled = false
        statusText.text = "MP4 视频已选择。可手动设置起点和时长；现在不再限制 3 秒，可直接测试更长动态照片。"
        updateGenerateEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun text(value: String, size: Float = 14f) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, dp(6), 0, dp(6))
        }
        fun secondsInput(defaultValue: String): EditText = EditText(this).apply {
            setText(defaultValue)
            hint = "秒"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(text("Motion Photo Maker", 28f))
        root.addView(text("微信兼容实验版：JPEG 封面 + H.264/AAC MP4。保留手动视频起点和时长，可测试超过 3 秒的实况照片。", 15f))

        root.addView(text("1 · 封面照片", 18f))
        coverPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFE7E7E7.toInt())
        }
        root.addView(coverPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        root.addView(Button(this).apply {
            text = "选择 JPEG 封面"
            setOnClickListener {
                coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        })
        coverNameText = text("尚未选择", 13f)
        root.addView(coverNameText)

        root.addView(text("2 · 动态视频", 18f))
        videoPreview = VideoView(this)
        root.addView(videoPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        root.addView(Button(this).apply {
            text = "选择 MP4 视频"
            setOnClickListener {
                videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        })
        videoNameText = text("尚未选择", 13f)
        root.addView(videoNameText)
        sourceDurationText = text("原视频时长：尚未选择", 13f)
        root.addView(sourceDurationText)

        root.addView(text("3 · 手动裁剪视频", 18f))
        val trimRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val startBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("开始位置（秒）", 13f))
            trimStartInput = secondsInput("0.0")
            addView(trimStartInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val durationBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("动态时长（秒）", 13f))
            trimDurationInput = secondsInput("3.0")
            addView(trimDurationInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        trimRow.addView(startBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        trimRow.addView(durationBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        root.addView(trimRow)
        root.addView(text("不再限制 3 秒。例如可以输入 0 / 5、2 / 10 来测试微信对 5 秒或 10 秒 Motion Photo 的接受情况。无损裁剪的开始位置会吸附到附近关键帧。", 12f))

        generateButton = Button(this).apply {
            text = "4 · 生成微信兼容 Motion Photo"
            isEnabled = false
            setOnClickListener { generateMotionPhoto() }
        }
        root.addView(generateButton)

        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progressBar)

        statusText = text(
            "等待选择素材。\n兼容策略：H.264/AVC + AAC、封面与视频宽高比一致、原厂风格 XMP、PresentationTimestampUs=0。视频时长由你手动决定。",
            14f
        ).apply { setTextIsSelectable(true) }
        root.addView(statusText)

        openResultButton = Button(this).apply {
            text = "在相册/Google Photos 中打开"
            isEnabled = false
            setOnClickListener { openGeneratedPhoto() }
        }
        root.addView(openResultButton)
        root.addView(text("输出目录：DCIM/MotionPhotoMaker\n文件名会以 MP.jpg 结尾。", 12f))

        return ScrollView(this).apply { addView(root) }
    }

    private fun generateMotionPhoto() {
        val cover = coverUri ?: return
        val video = videoUri ?: return
        val startSeconds = trimStartInput.text.toString().trim().toDoubleOrNull()
        val durationSeconds = trimDurationInput.text.toString().trim().toDoubleOrNull()
        if (startSeconds == null || startSeconds < 0.0) {
            statusText.text = "开始位置格式不正确。请输入大于等于 0 的秒数。"
            return
        }
        if (durationSeconds == null || durationSeconds < 0.1) {
            statusText.text = "动态时长格式不正确。请输入至少 0.1 秒。"
            return
        }
        val sourceMs = sourceVideoDurationMs
        if (sourceMs != null && sourceMs > 0 && startSeconds * 1000.0 >= sourceMs) {
            statusText.text = "开始位置已经超过原视频总时长。"
            return
        }

        val startUs = (startSeconds * 1_000_000.0).toLong()
        val durationUs = (durationSeconds * 1_000_000.0).toLong()
        setBusy(true)
        statusText.text = "正在生成……\n手动裁剪 ${String.format("%.3f", startSeconds)}s 起、${String.format("%.3f", durationSeconds)}s 长的视频，并匹配封面比例。"
        worker.execute {
            try {
                val result = MotionPhotoGenerator.generate(
                    applicationContext,
                    cover,
                    video,
                    startUs,
                    durationUs,
                )
                runOnUiThread {
                    resultUri = result.uri
                    setBusy(false)
                    openResultButton.isEnabled = true
                    statusText.text = "✓ 生成成功\n${result.displayName}\n请求裁剪：${String.format("%.3f", startSeconds)}s + ${String.format("%.3f", durationSeconds)}s\n实际输出视频：${result.videoWidth}×${result.videoHeight} / ${String.format("%.3f", result.durationUs / 1_000_000.0)} s\n视频大小：${formatBytes(result.videoBytes)}\n总大小：${formatBytes(result.totalBytes)}\n目录：DCIM/MotionPhotoMaker\n现在可以直接测试微信对该时长的实况发送支持。"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    statusText.text = "生成失败：${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    private fun openGeneratedPhoto() {
        val uri = resultUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            statusText.text = "文件已保存，但没有找到可打开 image/jpeg 的相册应用。"
        }
    }

    private fun updateGenerateEnabled() {
        generateButton.isEnabled = coverUri != null && videoUri != null
    }

    private fun setBusy(busy: Boolean) {
        generateButton.isEnabled = !busy && coverUri != null && videoUri != null
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
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

    private fun readDurationMs(uri: Uri): Long? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private fun formatBytes(bytes: Long): String {
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1.0) String.format("%.2f MiB", mib) else String.format("%.1f KiB", bytes / 1024.0)
    }

    override fun onDestroy() {
        super.onDestroy()
        videoPreview.stopPlayback()
        worker.shutdownNow()
    }
}

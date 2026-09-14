package com.example.motionphotomaker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var coverPreview: ImageView
    private lateinit var videoPreview: VideoView
    private lateinit var coverNameText: TextView
    private lateinit var videoNameText: TextView
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
        resultUri = null
        openResultButton.isEnabled = false
        statusText.text = "MP4 视频已选择。微信兼容模式会限制为 H.264/AAC，并最多保留前 3 秒。"
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(text("Motion Photo Maker", 28f))
        root.addView(text("微信兼容实验版：任意 JPEG 封面 + H.264/AAC MP4，自动裁剪比例与限制动态时长。", 15f))
        root.addView(text("1 · 封面照片", 18f))

        coverPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFE7E7E7.toInt())
        }
        root.addView(coverPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        root.addView(Button(this).apply {
            text = "选择 JPEG 封面"
            setOnClickListener { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        })
        coverNameText = text("尚未选择", 13f)
        root.addView(coverNameText)

        root.addView(text("2 · 动态视频", 18f))
        videoPreview = VideoView(this)
        root.addView(videoPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        root.addView(Button(this).apply {
            text = "选择 MP4 视频"
            setOnClickListener { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
        })
        videoNameText = text("尚未选择", 13f)
        root.addView(videoNameText)

        generateButton = Button(this).apply {
            text = "3 · 生成微信兼容 Motion Photo"
            isEnabled = false
            setOnClickListener { generateMotionPhoto() }
        }
        root.addView(generateButton)

        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progressBar)

        statusText = text(
            "等待选择素材。\n兼容策略：H.264/AVC + AAC、最长 3 秒、封面与视频宽高比一致、PresentationTimestampUs=0。",
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
        setBusy(true)
        statusText.text = "正在生成……\n检查视频编码、裁到最多 3 秒、匹配封面比例，然后写入 Motion Photo。"
        worker.execute {
            try {
                val result = MotionPhotoGenerator.generate(applicationContext, cover, video)
                runOnUiThread {
                    resultUri = result.uri
                    setBusy(false)
                    openResultButton.isEnabled = true
                    statusText.text = "✓ 生成成功\n${result.displayName}\n视频：${result.videoWidth}×${result.videoHeight} / ${String.format("%.2f", result.durationUs / 1_000_000.0)} s\n视频大小：${formatBytes(result.videoBytes)}\n总大小：${formatBytes(result.totalBytes)}\n目录：DCIM/MotionPhotoMaker\n请优先测试微信的‘实况’发送入口。"
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
        try { startActivity(intent) } catch (_: Throwable) {
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

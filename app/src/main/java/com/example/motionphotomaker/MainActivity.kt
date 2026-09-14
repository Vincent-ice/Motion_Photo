package com.example.motionphotomaker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

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
        if (uri != null) {
            coverUri = uri
            coverPreview.setImageURI(uri)
            coverNameText.text = displayName(uri) ?: uri.toString()
            resultUri = null
            openResultButton.isEnabled = false
            statusText.text = "JPEG 封面已选择。"
            updateGenerateEnabled()
        }
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            videoUri = uri
            videoNameText.text = displayName(uri) ?: uri.toString()
            videoPreview.setVideoURI(uri)
            val controller = MediaController(this).apply { setAnchorView(videoPreview) }
            videoPreview.setMediaController(controller)
            videoPreview.seekTo(1)
            resultUri = null
            openResultButton.isEnabled = false
            statusText.text = "MP4 视频已选择。点击视频区域可预览。"
            updateGenerateEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        coverPreview = findViewById(R.id.coverPreview)
        videoPreview = findViewById(R.id.videoPreview)
        coverNameText = findViewById(R.id.coverNameText)
        videoNameText = findViewById(R.id.videoNameText)
        statusText = findViewById(R.id.statusText)
        generateButton = findViewById(R.id.generateButton)
        openResultButton = findViewById(R.id.openResultButton)
        progressBar = findViewById(R.id.progressBar)

        findViewById<Button>(R.id.selectCoverButton).setOnClickListener {
            coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.selectVideoButton).setOnClickListener {
            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }

        generateButton.setOnClickListener { generateMotionPhoto() }
        openResultButton.setOnClickListener { openGeneratedPhoto() }
    }

    private fun generateMotionPhoto() {
        val cover = coverUri ?: return
        val video = videoUri ?: return
        setBusy(true)
        statusText.text = "正在生成……\n正在复制视频、写入 Motion Photo XMP，并保存到 MediaStore。"

        worker.execute {
            try {
                val result = MotionPhotoGenerator.generate(
                    context = applicationContext,
                    coverUri = cover,
                    videoUri = video,
                    presentationTimestampUs = -1L,
                )
                runOnUiThread {
                    resultUri = result.uri
                    setBusy(false)
                    openResultButton.isEnabled = true
                    statusText.text = buildString {
                        appendLine("✓ 生成成功")
                        appendLine(result.displayName)
                        appendLine("JPEG/XMP：${formatBytes(result.imageBytes)}")
                        appendLine("视频：${formatBytes(result.videoBytes)}")
                        appendLine("总大小：${formatBytes(result.totalBytes)}")
                        appendLine("目录：DCIM/MotionPhotoMaker")
                        appendLine("PresentationTimestampUs = -1")
                        if (result.replacedXmpPackets > 0) {
                            append("已替换原图中的 ${result.replacedXmpPackets} 个 XMP packet；EXIF/ICC 保留。")
                        }
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

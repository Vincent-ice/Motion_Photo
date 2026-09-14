package com.example.motionphotomaker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MotionPhotoGenerator {

    data class GenerateResult(
        val uri: Uri,
        val displayName: String,
        val imageBytes: Long,
        val videoBytes: Long,
        val totalBytes: Long,
        val replacedXmpPackets: Int,
    )

    fun generate(
        context: Context,
        coverUri: Uri,
        videoUri: Uri,
        presentationTimestampUs: Long = -1L,
    ): GenerateResult {
        val resolver = context.contentResolver

        val coverMime = resolver.getType(coverUri)
        require(coverMime == null || coverMime.equals("image/jpeg", ignoreCase = true)) {
            "第一版 Demo 只支持 JPEG 封面；当前类型：$coverMime"
        }

        val videoMime = resolver.getType(videoUri)
        require(videoMime == null || videoMime.equals("video/mp4", ignoreCase = true)) {
            "第一版 Demo 只支持 MP4 视频；当前类型：$videoMime"
        }

        val tempVideo = File.createTempFile("motion_photo_", ".mp4", context.cacheDir)
        var outputUri: Uri? = null

        try {
            resolver.openInputStream(videoUri)?.use { input ->
                tempVideo.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选视频。")

            val videoLength = tempVideo.length()
            require(videoLength > 0) { "视频文件为空。" }
            require(looksLikeMp4(tempVideo)) {
                "视频不像有效 MP4/ISO-BMFF 文件：开头附近没有 ftyp box。"
            }

            val jpegOriginal = resolver.openInputStream(coverUri)?.use { it.readBytes() }
                ?: error("无法读取所选封面。")

            val xmp = buildMotionPhotoXmp(videoLength, presentationTimestampUs)
            val injected = JpegXmpInjector.injectMotionXmp(jpegOriginal, xmp)
            val jpeg = injected.jpeg

            require(jpeg.size >= 2 && (jpeg[jpeg.size - 2].toInt() and 0xFF) == 0xFF &&
                (jpeg[jpeg.size - 1].toInt() and 0xFF) == 0xD9
            ) { "内部校验失败：JPEG 没有以 EOI 结束。" }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val displayName = "MOTION_${timestamp}_MP.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/MotionPhotoMaker"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val targetUri = resolver.insert(collection, values)
                ?: error("无法在 MediaStore 创建输出文件。")
            outputUri = targetUri

            resolver.openOutputStream(targetUri, "w")?.buffered()?.use { output ->
                output.write(jpeg)
                tempVideo.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: error("无法写入生成的 Motion Photo。")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(targetUri, values, null, null)

            return GenerateResult(
                uri = targetUri,
                displayName = displayName,
                imageBytes = jpeg.size.toLong(),
                videoBytes = videoLength,
                totalBytes = jpeg.size.toLong() + videoLength,
                replacedXmpPackets = injected.removedStandardXmp + injected.removedExtendedXmp,
            )
        } catch (t: Throwable) {
            outputUri?.let { resolver.delete(it, null, null) }
            throw t
        } finally {
            tempVideo.delete()
        }
    }

    private fun buildMotionPhotoXmp(videoLength: Long, timestampUs: Long): ByteArray {
        require(videoLength > 0)
        val xml = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="MotionPhotoMaker Android Demo 0.1">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                  xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  xmlns:Container="http://ns.google.com/photos/1.0/container/"
                  xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                  GCamera:MotionPhoto="1"
                  GCamera:MotionPhotoVersion="1"
                  GCamera:MotionPhotoPresentationTimestampUs="$timestampUs">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        return xml.toByteArray(Charsets.UTF_8)
    }

    private fun looksLikeMp4(file: File): Boolean {
        val prefix = ByteArray(64)
        val count = file.inputStream().use { it.read(prefix) }
        if (count < 4) return false
        for (i in 0..(count - 4)) {
            if (prefix[i] == 'f'.code.toByte() &&
                prefix[i + 1] == 't'.code.toByte() &&
                prefix[i + 2] == 'y'.code.toByte() &&
                prefix[i + 3] == 'p'.code.toByte()
            ) return true
        }
        return false
    }
}

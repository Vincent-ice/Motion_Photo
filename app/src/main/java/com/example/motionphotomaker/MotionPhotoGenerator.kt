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
        val videoWidth: Int,
        val videoHeight: Int,
        val durationUs: Long,
        val requestedStartMs: Long,
        val requestedEndMs: Long,
        val targetAspect: Float,
        val zoom: Float,
        val coverZoom: Float,
    )

    fun generate(
        context: Context,
        coverUri: Uri,
        videoUri: Uri,
        videoParams: VideoEditParams,
        coverParams: CoverEditParams,
    ): GenerateResult {
        val resolver = context.contentResolver
        val coverMime = resolver.getType(coverUri)
        require(
            coverMime == null ||
                coverMime.equals("image/jpeg", ignoreCase = true) ||
                coverMime.equals("image/png", ignoreCase = true)
        ) {
            "当前版本只支持 JPEG / PNG 封面；当前类型：$coverMime"
        }
        val videoMime = resolver.getType(videoUri)
        require(videoMime == null || videoMime.startsWith("video/", ignoreCase = true)) {
            "请选择有效视频；当前类型：$videoMime"
        }
        require(videoParams.startMs >= 0L)
        require(videoParams.endMs > videoParams.startMs)
        require(videoParams.targetAspect > 0f)
        require(kotlin.math.abs(videoParams.targetAspect - coverParams.targetAspect) < 0.0001f) {
            "封面和视频的目标比例不一致。"
        }

        val sourceVideo = File.createTempFile("motion_photo_source_", ".mp4", context.cacheDir)
        val editedVideo = File.createTempFile("motion_photo_edited_", ".mp4", context.cacheDir)
        var outputUri: Uri? = null

        try {
            resolver.openInputStream(videoUri)?.use { input ->
                sourceVideo.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            } ?: error("无法读取所选视频。")
            require(sourceVideo.length() > 0L) { "视频文件为空。" }

            val outputInfo = VideoCompat.exportEditedForWeChat(
                context = context,
                input = sourceVideo,
                output = editedVideo,
                params = videoParams,
            )
            val videoLength = editedVideo.length()
            require(videoLength > 0L) { "编辑后的视频为空。" }

            val coverOriginal = resolver.openInputStream(coverUri)?.use { it.readBytes() }
                ?: error("无法读取所选封面。")
            val croppedCover = VideoCompat.cropCover(coverOriginal, coverMime, coverParams)

            val xmp = buildMotionPhotoXmp(videoLength)
            val injected = JpegXmpInjector.injectMotionXmp(croppedCover, xmp)
            val jpeg = injected.jpeg
            require(
                jpeg.size >= 2 &&
                    (jpeg[jpeg.size - 2].toInt() and 0xFF) == 0xFF &&
                    (jpeg[jpeg.size - 1].toInt() and 0xFF) == 0xD9,
            ) { "内部校验失败：JPEG 没有以 EOI 结束。" }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val displayName = "IMG_${timestamp}_MP.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/MotionPhotoMaker",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Images.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY,
            )
            val targetUri = resolver.insert(collection, values)
                ?: error("无法在 MediaStore 创建输出文件。")
            outputUri = targetUri

            resolver.openOutputStream(targetUri, "w")?.buffered()?.use { output ->
                output.write(jpeg)
                editedVideo.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
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
                replacedXmpPackets = injected.removedStandardXmp +
                    injected.removedExtendedXmp,
                videoWidth = outputInfo.displayWidth,
                videoHeight = outputInfo.displayHeight,
                durationUs = outputInfo.durationUs,
                requestedStartMs = videoParams.startMs,
                requestedEndMs = videoParams.endMs,
                targetAspect = videoParams.targetAspect,
                zoom = videoParams.zoom,
                coverZoom = coverParams.zoom,
            )
        } catch (t: Throwable) {
            outputUri?.let { resolver.delete(it, null, null) }
            throw t
        } finally {
            sourceVideo.delete()
            editedVideo.delete()
        }
    }

    private fun buildMotionPhotoXmp(videoLength: Long): ByteArray {
        require(videoLength > 0)
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="MotionPhotoMaker Editor 0.5">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
      xmlns:Container="http://ns.google.com/photos/1.0/container/"
      xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
      Camera:ImageType="motionPhoto"
      Camera:MotionPhoto="1"
      Camera:MotionPhotoVersion="1"
      Camera:MotionPhotoPresentationTimestampUs="0"
      Container:Version="1">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="$videoLength"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>"""
        return xml.toByteArray(Charsets.UTF_8)
    }
}

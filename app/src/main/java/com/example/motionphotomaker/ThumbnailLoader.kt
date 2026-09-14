package com.example.motionphotomaker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

object ThumbnailLoader {
    fun load(context: Context, uri: Uri, durationMs: Long, count: Int = 10): List<Bitmap> {
        if (durationMs <= 0 || count <= 0) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            (0 until count).mapNotNull { index ->
                val fraction = if (count == 1) 0.0 else index.toDouble() / (count - 1).toDouble()
                val timeUs = (durationMs * 1000.0 * fraction).toLong()
                runCatching {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        240,
                        160,
                    )
                }.getOrNull()
            }
        } finally {
            retriever.release()
        }
    }
}

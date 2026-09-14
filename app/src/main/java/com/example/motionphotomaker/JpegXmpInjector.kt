package com.example.motionphotomaker

import java.io.ByteArrayOutputStream

object JpegXmpInjector {
    data class Result(
        val jpeg: ByteArray,
        val removedStandardXmp: Int = 0,
        val removedExtendedXmp: Int = 0,
    )

    fun injectMotionXmp(jpegInput: ByteArray, xmpXml: ByteArray): Result {
        require(jpegInput.size >= 4 && (jpegInput[0].toInt() and 0xFF) == 0xFF && (jpegInput[1].toInt() and 0xFF) == 0xD8) {
            "封面不是有效 JPEG。"
        }

        val eoi = findEoi(jpegInput)
        val cleanJpeg = jpegInput.copyOfRange(0, eoi + 2)
        val app1 = makeXmpApp1(xmpXml)

        val out = ByteArrayOutputStream(cleanJpeg.size + app1.size)
        out.write(cleanJpeg, 0, 2)
        out.write(app1)
        out.write(cleanJpeg, 2, cleanJpeg.size - 2)
        return Result(out.toByteArray())
    }

    private fun makeXmpApp1(xmpXml: ByteArray): ByteArray {
        val header = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val payload = header + xmpXml
        val length = payload.size + 2
        require(length <= 65535) { "XMP 数据过大。" }

        return ByteArrayOutputStream(payload.size + 4).apply {
            write(0xFF)
            write(0xE1)
            write((length ushr 8) and 0xFF)
            write(length and 0xFF)
            write(payload)
        }.toByteArray()
    }

    private fun findEoi(data: ByteArray): Int {
        for (i in data.size - 2 downTo 2) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD9) {
                return i
            }
        }
        error("找不到 JPEG EOI marker。")
    }
}

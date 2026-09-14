package com.example.motionphotomaker

import java.io.ByteArrayOutputStream

object JpegXmpInjector {
    data class Result(
        val jpeg: ByteArray,
        val removedStandardXmp: Int = 0,
        val removedExtendedXmp: Int = 0,
    )

    private val XMP_STD_HEADER =
        "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    private val XMP_EXT_HEADER =
        "http://ns.adobe.com/xmp/extension/".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    fun injectMotionXmp(jpegInput: ByteArray, xmpXml: ByteArray): Result {
        require(jpegInput.size >= 4 && u8(jpegInput[0]) == 0xFF && u8(jpegInput[1]) == 0xD8) {
            "封面不是有效 JPEG。"
        }

        val sosOffset = findSos(jpegInput)
        val eoiEnd = findEoiEnd(jpegInput, sosOffset)
        val jpeg = jpegInput.copyOfRange(0, eoiEnd)

        val out = ByteArrayOutputStream(jpeg.size + xmpXml.size + 128)
        out.write(0xFF)
        out.write(0xD8)

        var pos = 2
        var removedStd = 0
        var removedExt = 0

        while (pos < sosOffset) {
            val segment = readSegment(jpeg, pos)
            val isStdXmp = segment.marker == 0xE1 &&
                startsWith(jpeg, segment.payloadStart, segment.payloadEnd, XMP_STD_HEADER)
            val isExtXmp = segment.marker == 0xE1 &&
                startsWith(jpeg, segment.payloadStart, segment.payloadEnd, XMP_EXT_HEADER)

            when {
                isStdXmp -> removedStd++
                isExtXmp -> removedExt++
                else -> out.write(jpeg, pos, segment.end - pos)
            }
            pos = segment.end
        }

        out.write(makeXmpApp1(xmpXml))
        out.write(jpeg, sosOffset, jpeg.size - sosOffset)

        return Result(
            jpeg = out.toByteArray(),
            removedStandardXmp = removedStd,
            removedExtendedXmp = removedExt,
        )
    }

    private data class Segment(
        val marker: Int,
        val payloadStart: Int,
        val payloadEnd: Int,
        val end: Int,
    )

    private fun findSos(data: ByteArray): Int {
        var pos = 2
        while (pos < data.size) {
            require(u8(data[pos]) == 0xFF) { "JPEG marker 结构异常，offset=$pos" }
            val markerStart = pos
            val segment = readSegment(data, markerStart)
            if (segment.marker == 0xDA) return markerStart
            require(segment.marker != 0xD9) { "JPEG 在 SOS 前提前结束。" }
            pos = segment.end
        }
        error("找不到 JPEG SOS marker。")
    }

    private fun readSegment(data: ByteArray, markerStart: Int): Segment {
        var i = markerStart
        require(i < data.size && u8(data[i]) == 0xFF) { "无效 JPEG marker。" }
        while (i < data.size && u8(data[i]) == 0xFF) i++
        require(i < data.size) { "JPEG marker 截断。" }

        val marker = u8(data[i])
        if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) {
            return Segment(marker, i + 1, i + 1, i + 1)
        }

        require(i + 2 < data.size) { "JPEG segment 长度字段截断。" }
        val length = (u8(data[i + 1]) shl 8) or u8(data[i + 2])
        require(length >= 2) { "无效 JPEG segment 长度。" }

        val payloadStart = i + 3
        val end = i + 1 + length
        require(end <= data.size) { "JPEG segment 数据截断。" }

        return Segment(
            marker = marker,
            payloadStart = payloadStart,
            payloadEnd = end,
            end = end,
        )
    }

    private fun findEoiEnd(data: ByteArray, start: Int): Int {
        var i = start
        while (i + 1 < data.size) {
            if (u8(data[i]) == 0xFF && u8(data[i + 1]) == 0xD9) {
                return i + 2
            }
            i++
        }
        error("找不到 JPEG EOI marker。")
    }

    private fun makeXmpApp1(xmpXml: ByteArray): ByteArray {
        val payload = XMP_STD_HEADER + xmpXml
        val length = payload.size + 2
        require(length <= 0xFFFF) { "XMP 数据过大。" }

        return ByteArrayOutputStream(payload.size + 4).apply {
            write(0xFF)
            write(0xE1)
            write((length ushr 8) and 0xFF)
            write(length and 0xFF)
            write(payload)
        }.toByteArray()
    }

    private fun startsWith(
        data: ByteArray,
        start: Int,
        end: Int,
        prefix: ByteArray,
    ): Boolean {
        if (start < 0 || end > data.size || start + prefix.size > end) return false
        for (i in prefix.indices) {
            if (data[start + i] != prefix[i]) return false
        }
        return true
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}

package com.example.motionphotomaker

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small Surface -> OpenGL -> Surface transcoder.
 *
 * We intentionally keep the editing path independent from Media3 Transformer:
 * on some devices the Media3 GL effect chain can preview/export black frames.
 * Here MediaCodec decodes to a SurfaceTexture, our shader samples the selected
 * crop, and MediaCodec encodes that rendered surface to H.264.
 */
object NativeVideoTranscoder {
    private const val TIMEOUT_US = 10_000L
    private const val MIME_AVC = "video/avc"
    private const val MIME_AAC = "audio/mp4a-latm"

    fun transcode(
        input: File,
        output: File,
        params: VideoEditParams,
        sourceInfo: VideoCompat.VideoInfo,
    ) {
        val startUs = params.startMs * 1000L
        val endUs = params.endMs * 1000L
        require(endUs > startUs)

        val crop = CropMath.computePixels(
            sourceWidth = sourceInfo.displayWidth,
            sourceHeight = sourceInfo.displayHeight,
            targetAspect = params.targetAspect,
            zoom = params.zoom,
            panX = params.panX,
            panY = params.panY,
        )
        val (outputWidth, outputHeight) = CropMath.outputSize(
            sourceWidth = sourceInfo.displayWidth,
            sourceHeight = sourceInfo.displayHeight,
            targetAspect = params.targetAspect,
        )

        val probe = MediaExtractor()
        probe.setDataSource(input.absolutePath)
        val videoTrack = findTrack(probe, "video/")
        require(videoTrack >= 0) { "视频中没有可用的视频轨。" }
        val sourceVideoFormat = probe.getTrackFormat(videoTrack)
        val sourceVideoMime = sourceVideoFormat.getString(MediaFormat.KEY_MIME)
            ?: error("无法读取视频编码格式。")
        val audioTrack = findTrack(probe, "audio/")
        val sourceAudioFormat = if (audioTrack >= 0) probe.getTrackFormat(audioTrack) else null
        val copyAudio = sourceAudioFormat?.getString(MediaFormat.KEY_MIME) == MIME_AAC
        probe.release()

        val frameRate = runCatching {
            if (sourceVideoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                sourceVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else 30
        }.getOrDefault(30).coerceIn(1, 120)

        val bitrate = (
            outputWidth.toLong() *
                outputHeight.toLong() *
                frameRate.toLong() *
                12L / 100L
            ).coerceIn(1_500_000L, 20_000_000L).toInt()

        if (output.exists()) output.delete()

        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        extractor.selectTrack(videoTrack)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val encoderFormat = MediaFormat.createVideoFormat(MIME_AVC, outputWidth, outputHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MIME_AVC)
        val decoder = MediaCodec.createDecoderByType(sourceVideoMime)
        var codecInputSurface: CodecInputSurface? = null
        var decoderOutputSurface: DecoderOutputSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1

        try {
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface()
            codecInputSurface = CodecInputSurface(encoderSurface)
            codecInputSurface.makeCurrent()

            decoderOutputSurface = DecoderOutputSurface(
                sourceWidth = sourceInfo.displayWidth,
                sourceHeight = sourceInfo.displayHeight,
                crop = crop,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
            )

            decoder.configure(sourceVideoFormat, decoderOutputSurface.surface, null, 0)
            encoder.start()
            decoder.start()

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var signaledEncoderEos = false

            while (!encoderDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0L || sampleTime >= endUs) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                endUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: error("无法取得视频解码输入缓冲区。")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    endUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    sampleTime,
                                    extractor.sampleFlags,
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                if (!decoderDone) {
                    when (val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            val eos =
                                (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val render =
                                decoderInfo.presentationTimeUs >= startUs &&
                                    decoderInfo.presentationTimeUs < endUs &&
                                    decoderInfo.size > 0

                            decoder.releaseOutputBuffer(outputIndex, render)

                            if (render) {
                                decoderOutputSurface.awaitNewImage()
                                decoderOutputSurface.drawImage()
                                codecInputSurface.setPresentationTime(
                                    (decoderInfo.presentationTimeUs - startUs) * 1000L,
                                )
                                if (!codecInputSurface.swapBuffers()) {
                                    error("无法向视频编码器提交画面。")
                                }
                            }

                            if (eos) {
                                decoderDone = true
                                if (!signaledEncoderEos) {
                                    encoder.signalEndOfInputStream()
                                    signaledEncoderEos = true
                                }
                            }
                        }
                    }
                }

                var drainingEncoder = true
                while (drainingEncoder) {
                    when (val encoderStatus = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> drainingEncoder = false

                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "视频编码器重复报告输出格式。" }
                            muxer = MediaMuxer(
                                output.absolutePath,
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                            )
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (copyAudio && sourceAudioFormat != null) {
                                muxerAudioTrack = muxer.addTrack(sourceAudioFormat)
                            }
                            muxer.start()
                            muxerStarted = true
                        }

                        else -> if (encoderStatus >= 0) {
                            val encodedData = encoder.getOutputBuffer(encoderStatus)
                                ?: error("无法取得视频编码输出缓冲区。")

                            if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encoderInfo.size = 0
                            }

                            if (encoderInfo.size > 0) {
                                check(muxerStarted) { "编码数据早于 MediaMuxer 启动。" }
                                encodedData.position(encoderInfo.offset)
                                encodedData.limit(encoderInfo.offset + encoderInfo.size)
                                muxer!!.writeSampleData(
                                    muxerVideoTrack,
                                    encodedData,
                                    encoderInfo,
                                )
                            }

                            val eos =
                                (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(encoderStatus, false)
                            if (eos) {
                                encoderDone = true
                                drainingEncoder = false
                            }
                        }
                    }
                }

                if (decoderDone && !signaledEncoderEos) {
                    encoder.signalEndOfInputStream()
                    signaledEncoderEos = true
                }
            }

            check(muxerStarted) { "视频编码器没有产生可封装的输出。" }

            if (copyAudio && audioTrack >= 0 && muxerAudioTrack >= 0) {
                copyAacAudio(
                    input = input,
                    sourceTrackIndex = audioTrack,
                    destinationMuxer = muxer!!,
                    destinationTrackIndex = muxerAudioTrack,
                    startUs = startUs,
                    endUs = endUs,
                )
            }
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { decoderOutputSurface?.release() }
            runCatching { codecInputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }

        require(output.exists() && output.length() > 0L) { "视频转码结果为空。" }
    }

    private fun copyAacAudio(
        input: File,
        sourceTrackIndex: Int,
        destinationMuxer: MediaMuxer,
        destinationTrackIndex: Int,
        startUs: Long,
        endUs: Long,
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            extractor.selectTrack(sourceTrackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val format = extractor.getTrackFormat(sourceTrackIndex)
            val maxInput = runCatching {
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else 1024 * 1024
            }.getOrDefault(1024 * 1024).coerceAtLeast(64 * 1024)

            val buffer = ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L || sampleTime >= endUs) break

                if (sampleTime < startUs) {
                    if (!extractor.advance()) break
                    continue
                }

                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                info.set(
                    0,
                    size,
                    (sampleTime - startUs).coerceAtLeast(0L),
                    extractor.sampleFlags,
                )
                destinationMuxer.writeSampleData(destinationTrackIndex, buffer, info)
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private class CodecInputSurface(private val surface: Surface) {
        private var eglDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE

        init {
            eglSetup()
        }

        private fun eglSetup() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "无法取得 EGL display。" }

            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                "无法初始化 EGL。"
            }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    eglDisplay,
                    attribList,
                    0,
                    configs,
                    0,
                    configs.size,
                    numConfigs,
                    0,
                ),
            ) { "无法选择 EGLConfig。" }
            val config = configs[0] ?: error("没有可用 EGLConfig。")

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttribs,
                0,
            )
            checkEgl("eglCreateContext")

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                config,
                surface,
                surfaceAttribs,
                0,
            )
            checkEgl("eglCreateWindowSurface")
        }

        fun makeCurrent() {
            check(
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    eglSurface,
                    eglSurface,
                    eglContext,
                ),
            ) { "eglMakeCurrent 失败。" }
        }

        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            surface.release()
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun checkEgl(op: String) {
            val error = EGL14.eglGetError()
            check(error == EGL14.EGL_SUCCESS) {
                "$op: EGL error 0x${Integer.toHexString(error)}"
            }
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }

    private class DecoderOutputSurface(
        sourceWidth: Int,
        sourceHeight: Int,
        crop: CropRectPx,
        outputWidth: Int,
        outputHeight: Int,
    ) : SurfaceTexture.OnFrameAvailableListener {

        private val frameSyncObject = Object()
        private var frameAvailable = false
        private val released = AtomicBoolean(false)
        private val frameThread = HandlerThread("motion-photo-frame-listener").apply { start() }
        private val renderer = TextureRenderer(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            crop = crop,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
        )
        private val surfaceTexture: SurfaceTexture
        val surface: Surface

        init {
            renderer.surfaceCreated()
            surfaceTexture = SurfaceTexture(renderer.textureId)
            surfaceTexture.setOnFrameAvailableListener(this, Handler(frameThread.looper))
            surface = Surface(surfaceTexture)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            synchronized(frameSyncObject) {
                frameAvailable = true
                frameSyncObject.notifyAll()
            }
        }

        fun awaitNewImage() {
            synchronized(frameSyncObject) {
                while (!frameAvailable) {
                    frameSyncObject.wait(2_500L)
                    if (!frameAvailable) error("等待视频解码帧超时。")
                }
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
        }

        fun drawImage() {
            renderer.drawFrame(surfaceTexture)
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            surface.release()
            surfaceTexture.release()
            renderer.release()
            frameThread.quitSafely()
        }
    }

    private class TextureRenderer(
        sourceWidth: Int,
        sourceHeight: Int,
        crop: CropRectPx,
        private val outputWidth: Int,
        private val outputHeight: Int,
    ) {
        private val vertexBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(VERTICES.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(VERTICES)
                    position(0)
                }

        private val texBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(TEX_COORDS)
                    position(0)
                }

        private val stMatrix = FloatArray(16)
        private val cropMatrix = FloatArray(16)
        private val combinedMatrix = FloatArray(16)

        var textureId: Int = -1
            private set

        private var program = 0
        private var positionHandle = -1
        private var textureHandle = -1
        private var textureMatrixHandle = -1

        init {
            val left = crop.left / sourceWidth.toFloat()
            val right = crop.right / sourceWidth.toFloat()
            val canonicalBottom = 1f - crop.bottom / sourceHeight.toFloat()
            val canonicalTop = 1f - crop.top / sourceHeight.toFloat()

            Matrix.setIdentityM(cropMatrix, 0)
            Matrix.translateM(cropMatrix, 0, left, canonicalBottom, 0f)
            Matrix.scaleM(
                cropMatrix,
                0,
                (right - left).coerceAtLeast(0.0001f),
                (canonicalTop - canonicalBottom).coerceAtLeast(0.0001f),
                1f,
            )
        }

        fun surfaceCreated() {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            check(program != 0) { "无法创建 OpenGL shader program。" }

            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
            check(positionHandle >= 0 && textureHandle >= 0 && textureMatrixHandle >= 0) {
                "无法取得 OpenGL shader 参数。"
            }

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR.toFloat(),
            )
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat(),
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            checkGlError("surfaceCreated")
        }

        fun drawFrame(surfaceTexture: SurfaceTexture) {
            surfaceTexture.getTransformMatrix(stMatrix)
            Matrix.multiplyMM(combinedMatrix, 0, stMatrix, 0, cropMatrix, 0)

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer,
            )
            GLES20.glEnableVertexAttribArray(positionHandle)

            texBuffer.position(0)
            GLES20.glVertexAttribPointer(
                textureHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                texBuffer,
            )
            GLES20.glEnableVertexAttribArray(textureHandle)

            GLES20.glUniformMatrix4fv(
                textureMatrixHandle,
                1,
                false,
                combinedMatrix,
                0,
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()
            checkGlError("drawFrame")
        }

        fun release() {
            if (textureId >= 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = -1
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            check(program != 0) { "glCreateProgram failed" }
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, pixelShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                val info = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("OpenGL program link failed: $info")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(pixelShader)
            return program
        }

        private fun loadShader(shaderType: Int, source: String): Int {
            val shader = GLES20.glCreateShader(shaderType)
            check(shader != 0) { "glCreateShader failed" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("OpenGL shader compile failed: $info")
            }
            return shader
        }

        private fun checkGlError(op: String) {
            var error = GLES20.glGetError()
            if (error == GLES20.GL_NO_ERROR) return
            val errors = StringBuilder()
            while (error != GLES20.GL_NO_ERROR) {
                if (errors.isNotEmpty()) errors.append(", ")
                errors.append("0x").append(Integer.toHexString(error))
                error = GLES20.glGetError()
            }
            error("$op: OpenGL error $errors")
        }

        companion object {
            private val VERTICES = floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            )

            private val TEX_COORDS = floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            )

            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec4 aTextureCoord;
                uniform mat4 uTextureMatrix;
                varying vec2 vTextureCoord;
                void main() {
                    gl_Position = aPosition;
                    vTextureCoord = (uTextureMatrix * aTextureCoord).xy;
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTextureCoord);
                }
            """
        }
    }
}

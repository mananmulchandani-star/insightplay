package com.insightplay.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Decodes H.264 video frames from AirPlay mirroring using MediaCodec hardware decoder.
 * Renders directly to a Surface (SurfaceView) for low-latency display.
 */
class VideoRenderer {

    companion object {
        private const val TAG = "VideoRenderer"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val FRAME_TIMEOUT_US = 10_000L // 10ms timeout for dequeue
        private const val MAX_QUEUE_SIZE = 30
    }

    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var isRunning = false
    private var currentWidth = DEFAULT_WIDTH
    private var currentHeight = DEFAULT_HEIGHT

    private val frameQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var decoderJob: Job? = null

    // Callback for state changes
    var onResolutionChanged: ((width: Int, height: Int) -> Unit)? = null
    var onFirstFrameRendered: (() -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    private var firstFrameRendered = false

    /**
     * Initialize the video decoder with a target surface.
     */
    fun initialize(targetSurface: Surface, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT) {
        surface = targetSurface
        currentWidth = width
        currentHeight = height
        firstFrameRendered = false

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                // Prefer low latency
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            }

            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, targetSurface, null, 0)
                start()
            }

            isRunning = true
            startDecoderLoop()
            Log.i(TAG, "Video decoder initialized: ${width}x${height}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video decoder", e)
            onError?.invoke(e)
        }
    }

    /**
     * Queue an H.264 NAL unit for decoding.
     */
    fun queueFrame(nalUnit: ByteArray) {
        if (!isRunning) return

        // Drop oldest frame if queue is full (prevent lag buildup)
        if (frameQueue.remainingCapacity() == 0) {
            frameQueue.poll()
        }
        frameQueue.offer(nalUnit)
    }

    /**
     * Update the decoder for a new resolution.
     */
    fun updateResolution(width: Int, height: Int) {
        if (width == currentWidth && height == currentHeight) return

        Log.i(TAG, "Resolution change: ${currentWidth}x${currentHeight} -> ${width}x${height}")
        currentWidth = width
        currentHeight = height

        // Restart decoder with new resolution
        stop()
        surface?.let { initialize(it, width, height) }
        onResolutionChanged?.invoke(width, height)
    }

    /**
     * Start the decoder loop that pulls frames from the queue and decodes them.
     */
    private fun startDecoderLoop() {
        decoderJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    // Input: feed NAL units to decoder
                    val frame = frameQueue.poll(16, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        feedInputBuffer(frame)
                    }

                    // Output: render decoded frames
                    drainOutputBuffer()

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Decoder loop error", e)
                    delay(10)
                }
            }
        }
    }

    private fun feedInputBuffer(data: ByteArray) {
        val codec = decoder ?: return
        val inputIndex = codec.dequeueInputBuffer(FRAME_TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(data)
            codec.queueInputBuffer(
                inputIndex,
                0,
                data.size,
                System.nanoTime() / 1000, // presentation time in microseconds
                0
            )
        }
    }

    private fun drainOutputBuffer() {
        val codec = decoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(bufferInfo, FRAME_TIMEOUT_US)

        while (outputIndex >= 0) {
            // Release buffer and render to surface
            codec.releaseOutputBuffer(outputIndex, true)

            if (!firstFrameRendered) {
                firstFrameRendered = true
                onFirstFrameRendered?.invoke()
                Log.i(TAG, "First video frame rendered")
            }

            outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }

        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val newFormat = codec.outputFormat
            Log.i(TAG, "Output format changed: $newFormat")
        }
    }

    /**
     * Stop and release the decoder.
     */
    fun stop() {
        isRunning = false
        decoderJob?.cancel()
        frameQueue.clear()

        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping decoder", e)
        }
        decoder = null
        Log.i(TAG, "Video decoder stopped")
    }

    /**
     * Clean up all resources.
     */
    fun release() {
        stop()
        scope.cancel()
    }

    fun isActive(): Boolean = isRunning && decoder != null
}

package com.insightplay.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Handles audio output for AirPlay RAOP streams.
 * Supports PCM, AAC-ELD, and ALAC audio formats via AudioTrack API.
 */
class AudioRenderer {

    companion object {
        private const val TAG = "AudioRenderer"
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val DEFAULT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_QUEUE_SIZE = 100
    }

    enum class AudioCodec {
        PCM,
        AAC_ELD,
        ALAC
    }

    private var audioTrack: AudioTrack? = null
    private var isRunning = false
    private var currentCodec = AudioCodec.PCM
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var channels = 2

    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var playbackJob: Job? = null

    // Callbacks
    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackStopped: (() -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    private var hasStartedPlayback = false

    /**
     * Initialize the audio renderer with the specified format.
     */
    fun initialize(
        codec: AudioCodec = AudioCodec.PCM,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = 2
    ) {
        this.currentCodec = codec
        this.sampleRate = sampleRate
        this.channels = channels
        this.hasStartedPlayback = false

        try {
            val channelConfig = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                DEFAULT_ENCODING
            ) * 2 // Double buffer for smoother playback

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(DEFAULT_ENCODING)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isRunning = true
            startPlaybackLoop()

            Log.i(TAG, "Audio renderer initialized: codec=$codec, sampleRate=$sampleRate, channels=$channels")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio renderer", e)
            onError?.invoke(e)
        }
    }

    /**
     * Queue decoded PCM audio data for playback.
     */
    fun queueAudio(pcmData: ByteArray) {
        if (!isRunning) return

        // Drop oldest if queue is full
        if (audioQueue.remainingCapacity() == 0) {
            audioQueue.poll()
        }
        audioQueue.offer(pcmData)
    }

    /**
     * Queue raw audio data that may need decoding first.
     * For PCM, data is passed directly. For AAC-ELD/ALAC, decode before playback.
     */
    fun queueRawAudio(data: ByteArray, codec: AudioCodec) {
        when (codec) {
            AudioCodec.PCM -> queueAudio(data)
            AudioCodec.AAC_ELD -> {
                // AAC-ELD decoding: the java-airplay-lib handles this via FdkAacLib
                // The decoded PCM data is what we receive here
                queueAudio(data)
            }
            AudioCodec.ALAC -> {
                // ALAC: similarly, pre-decoded PCM arrives here
                queueAudio(data)
            }
        }
    }

    /**
     * Playback loop that writes PCM data to AudioTrack.
     */
    private fun startPlaybackLoop() {
        playbackJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val data = audioQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (data != null && data.isNotEmpty()) {
                        audioTrack?.write(data, 0, data.size)

                        if (!hasStartedPlayback) {
                            hasStartedPlayback = true
                            withContext(Dispatchers.Main) {
                                onPlaybackStarted?.invoke()
                            }
                            Log.i(TAG, "Audio playback started")
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Audio playback error", e)
                    delay(10)
                }
            }
        }
    }

    /**
     * Set volume (0.0 to 1.0).
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        audioTrack?.setVolume(clamped)
        Log.d(TAG, "Volume set to $clamped")
    }

    /**
     * Pause audio playback.
     */
    fun pause() {
        try {
            audioTrack?.pause()
            Log.d(TAG, "Audio paused")
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing audio", e)
        }
    }

    /**
     * Resume audio playback.
     */
    fun resume() {
        try {
            audioTrack?.play()
            Log.d(TAG, "Audio resumed")
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming audio", e)
        }
    }

    /**
     * Flush the audio buffer.
     */
    fun flush() {
        audioQueue.clear()
        try {
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing audio", e)
        }
    }

    /**
     * Stop playback and release resources.
     */
    fun stop() {
        isRunning = false
        playbackJob?.cancel()
        audioQueue.clear()

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio track", e)
        }
        audioTrack = null

        if (hasStartedPlayback) {
            onPlaybackStopped?.invoke()
            hasStartedPlayback = false
        }

        Log.i(TAG, "Audio renderer stopped")
    }

    /**
     * Clean up all resources.
     */
    fun release() {
        stop()
        scope.cancel()
    }

    fun isActive(): Boolean = isRunning && audioTrack != null
}

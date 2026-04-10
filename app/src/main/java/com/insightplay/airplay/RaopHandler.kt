package com.insightplay.airplay

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles RAOP (Remote Audio Output Protocol) for AirPlay audio streaming.
 * Manages audio session setup, RTP packet reception, and audio data extraction.
 */
class RaopHandler(private val port: Int = 5000) {

    companion object {
        private const val TAG = "RaopHandler"
        private const val RTP_HEADER_SIZE = 12
        private const val AUDIO_BUFFER_SIZE = 8192
    }

    enum class AudioFormat(val code: Int) {
        PCM(0),
        ALAC(1),
        AAC(2),
        AAC_ELD(3);

        companion object {
            fun fromCode(code: Int): AudioFormat = entries.firstOrNull { it.code == code } ?: PCM
        }
    }

    private var controlSocket: ServerSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentFormat = AudioFormat.AAC_ELD
    private var sampleRate = 44100
    private var channels = 2
    private var sampleSize = 16

    // Callbacks
    var onAudioData: ((data: ByteArray, format: AudioFormat) -> Unit)? = null
    var onSessionStarted: ((clientInfo: String) -> Unit)? = null
    var onSessionEnded: ((clientInfo: String) -> Unit)? = null
    var onVolumeChanged: ((volume: Float) -> Unit)? = null
    var onMetadataReceived: ((metadata: Map<String, String>) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    /**
     * Start the RAOP server for audio streaming.
     */
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "RAOP handler already running")
            return
        }

        scope.launch {
            try {
                // Control port - TCP for RTSP signaling
                controlSocket = ServerSocket(port)
                isRunning.set(true)
                Log.i(TAG, "RAOP server started on port $port")

                // Start audio receiver on a separate UDP port
                startAudioReceiver(port + 1)

                // Start timing handler
                startTimingHandler(port + 2)

                // Accept control connections
                while (isRunning.get()) {
                    try {
                        val clientSocket = controlSocket?.accept() ?: break
                        val clientId = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                        Log.i(TAG, "RAOP client connected: $clientId")
                        handleControlConnection(clientSocket, clientId)
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Error accepting RAOP connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RAOP server error", e)
                onError?.invoke(e)
            }
        }
    }

    /**
     * Handle RAOP control connection (RTSP-like protocol for audio).
     */
    private fun handleControlConnection(socket: Socket, clientId: String) {
        scope.launch {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                onSessionStarted?.invoke(clientId)

                val reader = input.bufferedReader()
                while (isRunning.get() && !socket.isClosed) {
                    val line = reader.readLine() ?: break

                    when {
                        line.startsWith("OPTIONS") -> {
                            sendRtspResponse(output, "200 OK", mapOf(
                                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, SET_PARAMETER, GET_PARAMETER"
                            ))
                        }

                        line.startsWith("ANNOUNCE") -> {
                            // Parse SDP to determine audio format
                            val headers = readHeaders(reader)
                            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
                            if (contentLength > 0) {
                                val body = ByteArray(contentLength)
                                input.read(body)
                                parseAnnounceSdp(String(body))
                            }
                            sendRtspResponse(output, "200 OK")
                        }

                        line.startsWith("SETUP") -> {
                            sendRtspResponse(output, "200 OK", mapOf(
                                "Transport" to "RTP/AVP/UDP;unicast;mode=record;server_port=${port + 1};control_port=${port + 3};timing_port=${port + 2}",
                                "Session" to "1",
                                "Audio-Jack-Status" to "connected; type=analog"
                            ))
                        }

                        line.startsWith("RECORD") -> {
                            sendRtspResponse(output, "200 OK", mapOf(
                                "Audio-Latency" to "11025"
                            ))
                        }

                        line.startsWith("SET_PARAMETER") -> {
                            val headers = readHeaders(reader)
                            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
                            if (contentLength > 0) {
                                val body = ByteArray(contentLength)
                                input.read(body)
                                handleSetParameter(headers, body)
                            }
                            sendRtspResponse(output, "200 OK")
                        }

                        line.startsWith("FLUSH") -> {
                            sendRtspResponse(output, "200 OK")
                        }

                        line.startsWith("TEARDOWN") -> {
                            sendRtspResponse(output, "200 OK")
                            break
                        }

                        line.startsWith("GET_PARAMETER") -> {
                            sendRtspResponse(output, "200 OK")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "RAOP control error for $clientId", e)
                }
            } finally {
                onSessionEnded?.invoke(clientId)
                try {
                    socket.close()
                } catch (_: Exception) {}
                Log.i(TAG, "RAOP client disconnected: $clientId")
            }
        }
    }

    /**
     * Start UDP audio receiver for RTP packets.
     */
    private fun startAudioReceiver(audioPort: Int) {
        scope.launch {
            try {
                audioSocket = DatagramSocket(audioPort)
                val buffer = ByteArray(AUDIO_BUFFER_SIZE)
                Log.i(TAG, "Audio receiver started on UDP port $audioPort")

                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        audioSocket?.receive(packet)

                        // Extract audio data from RTP packet
                        if (packet.length > RTP_HEADER_SIZE) {
                            val audioData = ByteArray(packet.length - RTP_HEADER_SIZE)
                            System.arraycopy(
                                packet.data,
                                packet.offset + RTP_HEADER_SIZE,
                                audioData,
                                0,
                                audioData.size
                            )
                            onAudioData?.invoke(audioData, currentFormat)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Audio receive error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio receiver startup error", e)
            }
        }
    }

    /**
     * Start NTP-like timing handler for synchronization.
     */
    private fun startTimingHandler(timingPort: Int) {
        scope.launch {
            try {
                timingSocket = DatagramSocket(timingPort)
                val buffer = ByteArray(128)
                Log.i(TAG, "Timing handler started on UDP port $timingPort")

                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        timingSocket?.receive(packet)

                        // Respond to timing requests with current timestamp
                        val response = createTimingResponse(packet.data, packet.offset, packet.length)
                        val responsePacket = DatagramPacket(
                            response,
                            response.size,
                            packet.address,
                            packet.port
                        )
                        timingSocket?.send(responsePacket)
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Timing error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Timing handler startup error", e)
            }
        }
    }

    // ─── Protocol Helpers ─────────────────────────────────────────────

    private fun readHeaders(reader: java.io.BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var line = reader.readLine()
        while (!line.isNullOrEmpty()) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                headers[line.substring(0, colonIndex).trim()] = line.substring(colonIndex + 1).trim()
            }
            line = reader.readLine()
        }
        return headers
    }

    private fun sendRtspResponse(
        output: java.io.OutputStream,
        status: String,
        headers: Map<String, String> = emptyMap()
    ) {
        val sb = StringBuilder()
        sb.appendLine("RTSP/1.0 $status")
        sb.appendLine("Server: InsightPlay/1.0")
        for ((key, value) in headers) {
            sb.appendLine("$key: $value")
        }
        sb.appendLine()
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun parseAnnounceSdp(sdp: String) {
        Log.d(TAG, "Parsing ANNOUNCE SDP:\n$sdp")

        // Extract codec info from SDP
        val lines = sdp.lines()
        for (line in lines) {
            when {
                line.startsWith("a=rtpmap:") -> {
                    when {
                        line.contains("L16") -> currentFormat = AudioFormat.PCM
                        line.contains("AppleLossless") -> currentFormat = AudioFormat.ALAC
                        line.contains("mpeg4-generic", ignoreCase = true) -> currentFormat = AudioFormat.AAC
                        line.contains("AAC-ELD", ignoreCase = true) -> currentFormat = AudioFormat.AAC_ELD
                    }
                }
                line.startsWith("a=fmtp:") -> {
                    // Parse format parameters (sample rate, channels, etc.)
                    val params = line.substringAfter(" ")
                    // Format: sr=44100;ch=2;ss=16 etc.
                    params.split(";").forEach { param ->
                        val parts = param.trim().split("=")
                        if (parts.size == 2) {
                            when (parts[0]) {
                                "sr" -> sampleRate = parts[1].toIntOrNull() ?: 44100
                                "ch" -> channels = parts[1].toIntOrNull() ?: 2
                                "ss" -> sampleSize = parts[1].toIntOrNull() ?: 16
                            }
                        }
                    }
                }
            }
        }

        Log.i(TAG, "Audio format: $currentFormat, sampleRate=$sampleRate, channels=$channels")
    }

    private fun handleSetParameter(headers: Map<String, String>, body: ByteArray) {
        val contentType = headers["Content-Type"] ?: return

        when {
            contentType.contains("text/parameters") -> {
                val text = String(body)
                // Handle volume
                if (text.startsWith("volume:")) {
                    val dbVolume = text.substringAfter("volume:").trim().toFloatOrNull()
                    if (dbVolume != null) {
                        // AirPlay volume is in dB (-144 to 0), convert to linear
                        val linearVolume = if (dbVolume <= -144f) 0f
                        else Math.pow(10.0, dbVolume / 20.0).toFloat().coerceIn(0f, 1f)

                        onVolumeChanged?.invoke(linearVolume)
                        Log.d(TAG, "Volume: ${dbVolume}dB -> $linearVolume linear")
                    }
                }
            }

            contentType.contains("application/x-dmap-tagged") -> {
                // DMAP metadata (track name, artist, album)
                val metadata = parseDmapMetadata(body)
                if (metadata.isNotEmpty()) {
                    onMetadataReceived?.invoke(metadata)
                }
            }

            contentType.contains("image/") -> {
                // Album artwork
                Log.d(TAG, "Received artwork: ${body.size} bytes")
            }
        }
    }

    private fun parseDmapMetadata(data: ByteArray): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        try {
            var offset = 0
            while (offset + 8 < data.size) {
                val tag = String(data, offset, 4, Charsets.US_ASCII)
                val length = ByteBuffer.wrap(data, offset + 4, 4).int
                offset += 8

                if (offset + length > data.size) break

                val value = String(data, offset, length, Charsets.UTF_8)
                when (tag) {
                    "minm" -> metadata["title"] = value
                    "asar" -> metadata["artist"] = value
                    "asal" -> metadata["album"] = value
                    "asgn" -> metadata["genre"] = value
                }
                offset += length
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing DMAP metadata", e)
        }
        return metadata
    }

    private fun createTimingResponse(data: ByteArray, offset: Int, length: Int): ByteArray {
        // Simple timing response with current NTP timestamp
        val response = ByteArray(32)
        // Copy request timestamp
        if (length >= 32) {
            System.arraycopy(data, offset, response, 0, minOf(32, length))
        }
        // Set response type
        response[0] = 0x80.toByte()
        response[1] = 0xD3.toByte() // Timing response type

        // Add current NTP time (simplified)
        val ntp = System.currentTimeMillis() / 1000L + 2208988800L
        ByteBuffer.wrap(response, 24, 8).putLong(ntp)

        return response
    }

    fun getAudioFormat(): AudioFormat = currentFormat
    fun getSampleRate(): Int = sampleRate
    fun getChannels(): Int = channels

    /**
     * Stop all RAOP services.
     */
    fun stop() {
        isRunning.set(false)
        try {
            controlSocket?.close()
            audioSocket?.close()
            timingSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing RAOP sockets", e)
        }
        controlSocket = null
        audioSocket = null
        timingSocket = null
        Log.i(TAG, "RAOP handler stopped")
    }

    /**
     * Clean up all resources.
     */
    fun release() {
        stop()
        scope.cancel()
    }
}

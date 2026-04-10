package com.insightplay.airplay

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles RTSP signaling for AirPlay screen mirroring.
 * Manages DESCRIBE, SETUP, PLAY, PAUSE, and TEARDOWN methods.
 * Works in conjunction with java-airplay-lib for the actual protocol handling.
 */
class RtspHandler(private val port: Int = 7000) {

    companion object {
        private const val TAG = "RtspHandler"
        private const val CSEQ = "CSeq"
        private const val CONTENT_LENGTH = "Content-Length"
        private const val USER_AGENT = "User-Agent"
    }

    enum class SessionState {
        IDLE,
        DESCRIBING,
        SETTING_UP,
        PLAYING,
        PAUSED,
        TEARDOWN
    }

    data class RtspRequest(
        val method: String,
        val uri: String,
        val version: String,
        val headers: Map<String, String>,
        val body: ByteArray? = null
    )

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = ConcurrentHashMap<String, SessionState>()

    // Callbacks
    var onSessionStarted: ((clientInfo: String) -> Unit)? = null
    var onSessionEnded: ((clientInfo: String) -> Unit)? = null
    var onVideoData: ((data: ByteArray) -> Unit)? = null
    var onMirroringStarted: (() -> Unit)? = null
    var onMirroringStopped: (() -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    /**
     * Start the RTSP server listening for mirroring connections.
     */
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "RTSP handler already running")
            return
        }

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                isRunning.set(true)
                Log.i(TAG, "RTSP server started on port $port")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        Log.i(TAG, "RTSP client connected: ${clientSocket.inetAddress.hostAddress}")
                        handleClient(clientSocket)
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Error accepting RTSP connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RTSP server error", e)
                onError?.invoke(e)
            }
        }
    }

    /**
     * Handle an individual RTSP client connection.
     */
    private fun handleClient(socket: Socket) {
        scope.launch {
            val clientId = "${socket.inetAddress.hostAddress}:${socket.port}"
            sessions[clientId] = SessionState.IDLE

            try {
                val input = socket.getInputStream()
                val reader = BufferedReader(InputStreamReader(input))
                val output = socket.getOutputStream()

                onSessionStarted?.invoke(clientId)

                while (isRunning.get() && !socket.isClosed) {
                    val request = readRequest(reader, input) ?: break
                    val response = processRequest(clientId, request)
                    sendResponse(output, response)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error handling RTSP client $clientId", e)
                }
            } finally {
                sessions.remove(clientId)
                onSessionEnded?.invoke(clientId)
                try {
                    socket.close()
                } catch (e: Exception) {
                    // ignore
                }
                Log.i(TAG, "RTSP client disconnected: $clientId")
            }
        }
    }

    /**
     * Read an RTSP request from the client.
     */
    private fun readRequest(
        reader: BufferedReader,
        input: java.io.InputStream
    ): RtspRequest? {
        // Read request line
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null

        val method = parts[0]
        val uri = parts[1]
        val version = parts[2]

        // Read headers
        val headers = mutableMapOf<String, String>()
        var line = reader.readLine()
        while (!line.isNullOrEmpty()) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
            line = reader.readLine()
        }

        // Read body if present
        var body: ByteArray? = null
        val contentLength = headers[CONTENT_LENGTH]?.toIntOrNull()
        if (contentLength != null && contentLength > 0) {
            body = ByteArray(contentLength)
            var bytesRead = 0
            while (bytesRead < contentLength) {
                val read = input.read(body, bytesRead, contentLength - bytesRead)
                if (read == -1) break
                bytesRead += read
            }
        }

        Log.d(TAG, "RTSP $method $uri (CSeq: ${headers[CSEQ]})")
        return RtspRequest(method, uri, version, headers, body)
    }

    /**
     * Process an RTSP request and generate response.
     */
    private fun processRequest(clientId: String, request: RtspRequest): String {
        val cseq = request.headers[CSEQ] ?: "0"

        return when (request.method.uppercase()) {
            "OPTIONS" -> {
                buildResponse(200, "OK", cseq, mapOf(
                    "Public" to "DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
                ))
            }

            "DESCRIBE" -> {
                sessions[clientId] = SessionState.DESCRIBING
                val sdp = generateSdp()
                buildResponse(200, "OK", cseq, mapOf(
                    "Content-Type" to "application/sdp",
                    "Content-Length" to sdp.length.toString()
                ), sdp)
            }

            "SETUP" -> {
                sessions[clientId] = SessionState.SETTING_UP
                val sessionId = generateSessionId()
                buildResponse(200, "OK", cseq, mapOf(
                    "Session" to sessionId,
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"
                ))
            }

            "PLAY" -> {
                sessions[clientId] = SessionState.PLAYING
                scope.launch(Dispatchers.Main) {
                    onMirroringStarted?.invoke()
                }
                buildResponse(200, "OK", cseq, mapOf(
                    "Range" to "npt=0.000-"
                ))
            }

            "PAUSE" -> {
                sessions[clientId] = SessionState.PAUSED
                buildResponse(200, "OK", cseq)
            }

            "TEARDOWN" -> {
                sessions[clientId] = SessionState.TEARDOWN
                scope.launch(Dispatchers.Main) {
                    onMirroringStopped?.invoke()
                }
                buildResponse(200, "OK", cseq)
            }

            "GET_PARAMETER" -> {
                buildResponse(200, "OK", cseq)
            }

            "SET_PARAMETER" -> {
                // Handle volume, metadata, etc.
                handleSetParameter(request)
                buildResponse(200, "OK", cseq)
            }

            "RECORD" -> {
                sessions[clientId] = SessionState.PLAYING
                onMirroringStarted?.invoke()
                buildResponse(200, "OK", cseq)
            }

            "FLUSH" -> {
                buildResponse(200, "OK", cseq)
            }

            else -> {
                Log.w(TAG, "Unknown RTSP method: ${request.method}")
                buildResponse(405, "Method Not Allowed", cseq)
            }
        }
    }

    /**
     * Build an RTSP response string.
     */
    private fun buildResponse(
        statusCode: Int,
        statusText: String,
        cseq: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: String? = null
    ): String {
        val sb = StringBuilder()
        sb.appendLine("RTSP/1.0 $statusCode $statusText")
        sb.appendLine("$CSEQ: $cseq")
        sb.appendLine("Server: InsightPlay/1.0")

        for ((key, value) in extraHeaders) {
            sb.appendLine("$key: $value")
        }

        if (body != null) {
            sb.appendLine("Content-Length: ${body.length}")
            sb.appendLine()
            sb.append(body)
        } else {
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Send response to client.
     */
    private fun sendResponse(output: OutputStream, response: String) {
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /**
     * Generate SDP session description.
     */
    private fun generateSdp(): String {
        return """
            v=0
            o=InsightPlay 0 0 IN IP4 0.0.0.0
            s=InsightPlay
            c=IN IP4 0.0.0.0
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96
            m=audio 0 RTP/AVP 97
            a=rtpmap:97 MPEG4-GENERIC/44100/2
        """.trimIndent()
    }

    /**
     * Handle SET_PARAMETER requests (volume, metadata, etc).
     */
    private fun handleSetParameter(request: RtspRequest) {
        val body = request.body?.toString(Charsets.UTF_8) ?: return
        Log.d(TAG, "SET_PARAMETER body: $body")
        // Handle volume, progress, artwork data here
    }

    private fun generateSessionId(): String {
        return System.currentTimeMillis().toString(16).uppercase()
    }

    /**
     * Get the current state of a session.
     */
    fun getSessionState(clientId: String): SessionState {
        return sessions[clientId] ?: SessionState.IDLE
    }

    /**
     * Get all active sessions.
     */
    fun getActiveSessions(): Map<String, SessionState> = sessions.toMap()

    /**
     * Check if any session is currently streaming.
     */
    fun hasActiveStream(): Boolean {
        return sessions.values.any { it == SessionState.PLAYING }
    }

    /**
     * Stop the RTSP server.
     */
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing RTSP server socket", e)
        }
        serverSocket = null
        sessions.clear()
        Log.i(TAG, "RTSP handler stopped")
    }

    /**
     * Clean up all resources.
     */
    fun release() {
        stop()
        scope.cancel()
    }


}

package com.insightplay.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Surface
import com.github.serezhka.jap2lib.AirPlay
import com.github.serezhka.jap2server.AirPlayServer
import com.github.serezhka.jap2server.AirplayDataConsumer
import kotlinx.coroutines.*
import java.net.NetworkInterface

/**
 * Core foreground service managing all AirPlay connections.
 * Orchestrates mDNS advertisement, RTSP/RAOP handlers, and media renderers.
 * Persists across app backgrounding and restarts on boot via BootReceiver.
 */
class AirPlayService : Service() {

    companion object {
        private const val TAG = "AirPlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "airplay_service_channel"

        // Actions
        const val ACTION_START = "com.insightplay.airplay.START"
        const val ACTION_STOP = "com.insightplay.airplay.STOP"

        // Broadcast events
        const val BROADCAST_STATE_CHANGED = "com.insightplay.airplay.STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_SENDER_NAME = "sender_name"
        const val EXTRA_ERROR = "error"

        // States
        const val STATE_IDLE = 0
        const val STATE_ADVERTISING = 1
        const val STATE_CONNECTING = 2
        const val STATE_STREAMING = 3
        const val STATE_ERROR = 4
        const val STATE_NO_NETWORK = 5
    }

    // Components
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    private var videoRenderer: VideoRenderer? = null
    private var audioRenderer: AudioRenderer? = null

    // java-airplay-server integration
    private var airPlayServer: AirPlayServer? = null

    // State
    private var currentState = STATE_IDLE
    private var deviceName = "InsightPlay"
    private var senderName: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val binder = LocalBinder()

    // Surface for video rendering (set by Activity)
    private var renderSurface: Surface? = null

    // Network monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkAvailable = false

    // ─── Binder ──────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): AirPlayService = this@AirPlayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ─── Service Lifecycle ───────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AirPlayService created")

        createNotificationChannel()
        acquireWakeLock()
        detectDeviceName()
        setupNetworkMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAirPlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_text_idle)))
                startAirPlay()
            }
        }

        // START_STICKY ensures the service restarts if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAirPlay()
        releaseWakeLock()
        teardownNetworkMonitoring()
        scope.cancel()
        Log.i(TAG, "AirPlayService destroyed")
    }

    // ─── AirPlay Control ─────────────────────────────────────────────

    /**
     * Start the AirPlay receiver stack.
     */
    private fun startAirPlay() {
        if (currentState == STATE_ADVERTISING || currentState == STATE_STREAMING) {
            Log.w(TAG, "AirPlay already active")
            return
        }

        if (!isNetworkAvailable) {
            updateState(STATE_NO_NETWORK)
            // Will retry when network becomes available
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val macAddress = getDeviceMacAddress()

                // Initialize mDNS advertiser
                mdnsAdvertiser = MdnsAdvertiser(this@AirPlayService).apply {
                    setDeviceName(deviceName)
                    setDeviceMac(macAddress)
                    startAdvertising()
                }

                // Initialize java-airplay-server
                startAirPlayServer(macAddress)

                withContext(Dispatchers.Main) {
                    updateState(STATE_ADVERTISING)
                }

                Log.i(TAG, "AirPlay receiver started as '$deviceName'")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AirPlay", e)
                withContext(Dispatchers.Main) {
                    updateState(STATE_ERROR, error = e.message)
                }
            }
        }
    }

    /**
     * Start the java-airplay-server for protocol handling.
     */
    private fun startAirPlayServer(macAddress: String) {
        try {
            val airPlayPort = MdnsAdvertiser.AIRPLAY_PORT
            val airTunesPort = MdnsAdvertiser.RAOP_PORT

            val consumer = object : AirplayDataConsumer {
                override fun onVideo(video: ByteArray) {
                    // Feed H.264 NAL units to video renderer
                    videoRenderer?.queueFrame(video)
                }

                override fun onVideoFormat(videoStreamInfo: com.github.serezhka.jap2lib.rtsp.VideoStreamInfo) {
                    Log.i(TAG, "Video format received")
                }

                override fun onAudio(audio: ByteArray) {
                    // Feed decoded audio to audio renderer
                    audioRenderer?.queueAudio(audio)
                }

                override fun onAudioFormat(audioStreamInfo: com.github.serezhka.jap2lib.rtsp.AudioStreamInfo) {
                    Log.i(TAG, "Audio format received")
                }
            }

            airPlayServer = AirPlayServer(deviceName, airPlayPort, airTunesPort, consumer)

            scope.launch(Dispatchers.IO) {
                try {
                    airPlayServer?.start()
                } catch (e: Exception) {
                    Log.e(TAG, "AirPlay server error", e)
                }
            }

            Log.i(TAG, "AirPlay server started on ports $airPlayPort/$airTunesPort")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AirPlay server", e)
            throw e
        }
    }

    /**
     * Stop the AirPlay receiver stack.
     */
    fun stopAirPlay() {
        scope.launch(Dispatchers.IO) {
            try {
                airPlayServer?.stop()
                mdnsAdvertiser?.stopAdvertising()
                videoRenderer?.stop()
                audioRenderer?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AirPlay", e)
            }
        }

        airPlayServer = null
        mdnsAdvertiser?.destroy()
        mdnsAdvertiser = null
        videoRenderer?.release()
        videoRenderer = null
        audioRenderer?.release()
        audioRenderer = null

        updateState(STATE_IDLE)
        Log.i(TAG, "AirPlay receiver stopped")
    }

    // ─── Connection Handling ─────────────────────────────────────────

    /**
     * Called when a client connects and starts streaming.
     */
    fun handleConnection(clientName: String) {
        senderName = clientName
        updateState(STATE_STREAMING, senderName = clientName)
        updateNotification(getString(R.string.notification_text_connected, clientName))
        Log.i(TAG, "Client connected: $clientName")
    }

    /**
     * Called when a client disconnects.
     */
    fun handleDisconnection() {
        val previousSender = senderName
        senderName = null

        videoRenderer?.stop()
        audioRenderer?.stop()

        updateState(STATE_ADVERTISING)
        updateNotification(getString(R.string.notification_text_idle))
        Log.i(TAG, "Client disconnected: $previousSender")
    }

    // ─── Surface Management ──────────────────────────────────────────

    /**
     * Set the Surface for video rendering (called by MainActivity).
     */
    fun setSurface(surface: Surface?) {
        renderSurface = surface
        if (surface != null) {
            // Initialize video renderer with the surface
            videoRenderer = VideoRenderer().apply {
                onFirstFrameRendered = {
                    scope.launch(Dispatchers.Main) {
                        handleConnection("Apple Device")
                    }
                }
                onError = { e ->
                    Log.e(TAG, "Video renderer error", e)
                }
                initialize(surface)
            }

            // Initialize audio renderer
            audioRenderer = AudioRenderer().apply {
                onPlaybackStarted = {
                    Log.i(TAG, "Audio playback started")
                }
                onError = { e ->
                    Log.e(TAG, "Audio renderer error", e)
                }
                initialize()
            }
        } else {
            videoRenderer?.stop()
            audioRenderer?.stop()
        }
    }

    // ─── State Management ────────────────────────────────────────────

    private fun updateState(newState: Int, senderName: String? = null, error: String? = null) {
        currentState = newState

        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, newState)
            senderName?.let { putExtra(EXTRA_SENDER_NAME, it) }
            error?.let { putExtra(EXTRA_ERROR, it) }
            setPackage(packageName)
        }
        sendBroadcast(intent)

        Log.d(TAG, "State updated: $newState (sender=$senderName, error=$error)")
    }

    fun getCurrentState(): Int = currentState
    fun getDeviceName(): String = deviceName

    // ─── Notification ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW // Silent
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_airplay_badge)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // ─── Network Monitoring ──────────────────────────────────────────

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                isNetworkAvailable = true
                if (currentState == STATE_NO_NETWORK) {
                    scope.launch { startAirPlay() }
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost")
                isNetworkAvailable = false
                updateState(STATE_NO_NETWORK)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (hasWifi && !isNetworkAvailable) {
                    isNetworkAvailable = true
                    if (currentState == STATE_NO_NETWORK) {
                        scope.launch { startAirPlay() }
                    }
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)

        // Check current state
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager?.getNetworkCapabilities(it) }
        isNetworkAvailable = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun teardownNetworkMonitoring() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback", e)
            }
        }
    }

    // ─── Device Info ─────────────────────────────────────────────────

    private fun detectDeviceName() {
        // Try to get Fire TV device name from settings
        deviceName = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
                ?: Settings.Global.getString(contentResolver, "bluetooth_name")
                ?: Build.MODEL
                ?: "InsightPlay"
        } catch (e: Exception) {
            Build.MODEL ?: "InsightPlay"
        }
        Log.i(TAG, "Device name: $deviceName")
    }

    private fun getDeviceMacAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val macBytes = NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.name.equals("wlan0", ignoreCase = true) }
                ?.hardwareAddress

            if (macBytes != null) {
                macBytes.joinToString(":") { "%02X".format(it) }
            } else {
                // Generate a consistent pseudo-MAC based on device
                val hash = (Build.SERIAL + Build.MODEL).hashCode()
                "AA:BB:%02X:%02X:%02X:%02X".format(
                    (hash shr 24) and 0xFF,
                    (hash shr 16) and 0xFF,
                    (hash shr 8) and 0xFF,
                    hash and 0xFF
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get MAC address", e)
            "AA:BB:CC:DD:EE:FF"
        }
    }

    // ─── Wake Lock ───────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "InsightPlay::AirPlayWakeLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock", e)
        }
    }
}

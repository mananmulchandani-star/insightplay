package com.insightplay.airplay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.insightplay.airplay.databinding.ActivityMainBinding

/**
 * Main receiver screen for Insight Play.
 * Displays device name, animated pulse ring, and handles AirPlay state transitions.
 * Pure receiver mode — no buttons, no menus.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SENDER_DISPLAY_DURATION = 2000L // Show sender name for 2 seconds
    }

    private lateinit var binding: ActivityMainBinding

    // Service binding
    private var airPlayService: AirPlayService? = null
    private var isBound = false

    // Animations
    private var pulseAnimator: ObjectAnimator? = null
    private var pulseRotationAnimator: ObjectAnimator? = null

    // Network retry
    private val handler = Handler(Looper.getMainLooper())
    private var networkRetryRunnable: Runnable? = null

    // State broadcast receiver
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AirPlayService.BROADCAST_STATE_CHANGED) {
                val state = intent.getIntExtra(AirPlayService.EXTRA_STATE, AirPlayService.STATE_IDLE)
                val senderName = intent.getStringExtra(AirPlayService.EXTRA_SENDER_NAME)
                val error = intent.getStringExtra(AirPlayService.EXTRA_ERROR)
                handleStateChange(state, senderName, error)
            }
        }
    }

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as AirPlayService.LocalBinder
            airPlayService = localBinder.getService()
            isBound = true
            Log.i(TAG, "Bound to AirPlayService")

            // Set up the video surface
            setupVideoSurface()

            // Update UI with current state
            updateDeviceName()
            handleStateChange(
                airPlayService?.getCurrentState() ?: AirPlayService.STATE_IDLE,
                null, null
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
            airPlayService = null
            isBound = false
            Log.w(TAG, "Disconnected from AirPlayService")
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive mode
        setupFullscreen()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start pulse animation on logo
        startPulseAnimation()

        // Bind to service
        bindAirPlayService()

        // Register state receiver
        val filter = IntentFilter(AirPlayService.BROADCAST_STATE_CHANGED)
        registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        setupFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulseAnimation()
        stopNetworkRetry()

        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {}

        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // ─── Fullscreen ──────────────────────────────────────────────────

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    // ─── Service Binding ─────────────────────────────────────────────

    private fun bindAirPlayService() {
        val intent = Intent(this, AirPlayService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ─── Video Surface ───────────────────────────────────────────────

    private fun setupVideoSurface() {
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Video surface created")
                airPlayService?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "Video surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Video surface destroyed")
                airPlayService?.setSurface(null)
            }
        })
    }

    // ─── UI Updates ──────────────────────────────────────────────────

    private fun updateDeviceName() {
        val name = airPlayService?.getDeviceName() ?: "InsightPlay"
        binding.deviceName.text = name
    }

    /**
     * Handle state changes from the AirPlay service.
     */
    private fun handleStateChange(state: Int, senderName: String?, error: String?) {
        Log.d(TAG, "State change: $state, sender=$senderName, error=$error")

        when (state) {
            AirPlayService.STATE_IDLE -> {
                showReceiverUI()
                binding.readyMessage.text = getString(R.string.ready_message)
                binding.networkError.visibility = View.GONE
            }

            AirPlayService.STATE_ADVERTISING -> {
                showReceiverUI()
                binding.readyMessage.text = getString(R.string.ready_message)
                binding.networkError.visibility = View.GONE
                stopNetworkRetry()
            }

            AirPlayService.STATE_CONNECTING -> {
                binding.readyMessage.text = getString(R.string.connecting)
            }

            AirPlayService.STATE_STREAMING -> {
                // Show sender name briefly, then go fullscreen
                if (senderName != null) {
                    showSenderName(senderName)
                }
            }

            AirPlayService.STATE_ERROR -> {
                showReceiverUI()
                binding.readyMessage.text = error ?: getString(R.string.error_service)
            }

            AirPlayService.STATE_NO_NETWORK -> {
                showReceiverUI()
                binding.networkError.visibility = View.VISIBLE
                binding.readyMessage.text = getString(R.string.waiting_network)
                startNetworkRetry()
            }
        }
    }

    /**
     * Show the idle receiver UI (device name + ready message).
     */
    private fun showReceiverUI() {
        binding.receiverOverlay.visibility = View.VISIBLE
        binding.surfaceView.visibility = View.GONE
        binding.senderName.visibility = View.GONE
        binding.deviceName.visibility = View.VISIBLE
        binding.readyMessage.visibility = View.VISIBLE
        binding.logoContainer.visibility = View.VISIBLE
        binding.airplayBadge.visibility = View.VISIBLE
    }

    /**
     * Show sender name for 2 seconds, then enter fullscreen video mode.
     */
    private fun showSenderName(name: String) {
        // Show sender name
        binding.senderName.text = getString(R.string.connected_to, name)
        binding.senderName.visibility = View.VISIBLE
        binding.deviceName.visibility = View.GONE
        binding.readyMessage.visibility = View.GONE

        // Animate fade-in
        binding.senderName.alpha = 0f
        binding.senderName.animate()
            .alpha(1f)
            .setDuration(300)
            .start()

        // After 2 seconds, go to fullscreen video
        handler.postDelayed({
            enterFullscreenStreaming()
        }, SENDER_DISPLAY_DURATION)
    }

    /**
     * Enter fullscreen streaming mode — hide all UI, show video surface.
     */
    private fun enterFullscreenStreaming() {
        binding.receiverOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.receiverOverlay.visibility = View.GONE
                binding.receiverOverlay.alpha = 1f
            }
            .start()

        binding.surfaceView.visibility = View.VISIBLE
    }

    // ─── Pulse Animation ─────────────────────────────────────────────

    /**
     * Start the purple glow pulse ring animation (2s loop).
     */
    private fun startPulseAnimation() {
        val pulseView = binding.pulseRing

        // Scale pulse
        pulseAnimator = ObjectAnimator.ofFloat(pulseView, View.SCALE_X, 0.85f, 1.15f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Also animate Y scale
        ObjectAnimator.ofFloat(pulseView, View.SCALE_Y, 0.85f, 1.15f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Alpha pulse
        ObjectAnimator.ofFloat(pulseView, View.ALPHA, 0.4f, 1f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Rotation (slow sweep effect)
        pulseRotationAnimator = ObjectAnimator.ofFloat(pulseView, View.ROTATION, 0f, 360f).apply {
            duration = 8000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseRotationAnimator?.cancel()
    }

    // ─── Network Retry ───────────────────────────────────────────────

    private fun startNetworkRetry() {
        stopNetworkRetry()
        networkRetryRunnable = object : Runnable {
            override fun run() {
                if (checkNetworkAvailable()) {
                    // Network is back — service will detect and restart
                    binding.networkError.visibility = View.GONE
                    stopNetworkRetry()
                } else {
                    handler.postDelayed(this, 10_000) // Retry every 10 seconds
                }
            }
        }
        handler.postDelayed(networkRetryRunnable!!, 10_000)
    }

    private fun stopNetworkRetry() {
        networkRetryRunnable?.let { handler.removeCallbacks(it) }
        networkRetryRunnable = null
    }

    private fun checkNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

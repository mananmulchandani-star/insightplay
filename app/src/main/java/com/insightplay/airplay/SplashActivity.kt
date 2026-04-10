package com.insightplay.airplay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.insightplay.airplay.databinding.ActivitySplashBinding

/**
 * Splash screen activity with custom animations.
 * Displays logo, "Insight Play" wordmark, and "Cast Anything. Instantly." tagline.
 * Animates: fade-in (600ms) → gentle pulse → transition to MainActivity at 1.8s.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DURATION = 1800L // 1.8 seconds total
        private const val FADE_IN_DURATION = 600L
        private const val PULSE_DURATION = 400L
        private const val PULSE_DELAY = 700L
    }

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start the AirPlay service immediately (background)
        startAirPlayService()

        // Begin animations
        startSplashAnimations()

        // Transition to MainActivity after splash duration
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToMain()
        }, SPLASH_DURATION)
    }

    /**
     * Animated splash sequence:
     * 1. Logo icon fades in + scales up (600ms)
     * 2. Wordmark fades in (600ms, slight delay)
     * 3. Tagline fades in (600ms, more delay)
     * 4. Logo pulses once gently (400ms, after fade completes)
     */
    private fun startSplashAnimations() {
        val logoIcon = binding.splashLogoIcon
        val wordmark = binding.splashWordmarkContainer
        val tagline = binding.splashTagline

        // 1. Logo fade-in + scale
        val logoFadeIn = ObjectAnimator.ofFloat(logoIcon, View.ALPHA, 0f, 1f).apply {
            duration = FADE_IN_DURATION
            interpolator = AccelerateDecelerateInterpolator()
        }
        val logoScaleX = ObjectAnimator.ofFloat(logoIcon, View.SCALE_X, 0.8f, 1f).apply {
            duration = FADE_IN_DURATION
            interpolator = OvershootInterpolator(1.5f)
        }
        val logoScaleY = ObjectAnimator.ofFloat(logoIcon, View.SCALE_Y, 0.8f, 1f).apply {
            duration = FADE_IN_DURATION
            interpolator = OvershootInterpolator(1.5f)
        }

        // 2. Wordmark fade-in (starts 100ms after logo)
        val wordmarkFadeIn = ObjectAnimator.ofFloat(wordmark, View.ALPHA, 0f, 1f).apply {
            duration = FADE_IN_DURATION
            startDelay = 100
            interpolator = AccelerateDecelerateInterpolator()
        }

        // 3. Tagline fade-in (starts 200ms after logo)
        val taglineFadeIn = ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 1f).apply {
            duration = FADE_IN_DURATION
            startDelay = 200
            interpolator = AccelerateDecelerateInterpolator()
        }

        // 4. Gentle pulse after fade-in completes
        val pulseScaleX = ObjectAnimator.ofFloat(logoIcon, View.SCALE_X, 1f, 1.08f, 1f).apply {
            duration = PULSE_DURATION
            startDelay = PULSE_DELAY
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseScaleY = ObjectAnimator.ofFloat(logoIcon, View.SCALE_Y, 1f, 1.08f, 1f).apply {
            duration = PULSE_DURATION
            startDelay = PULSE_DELAY
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Play all together
        AnimatorSet().apply {
            playTogether(
                logoFadeIn, logoScaleX, logoScaleY,
                wordmarkFadeIn, taglineFadeIn,
                pulseScaleX, pulseScaleY
            )
            start()
        }
    }

    /**
     * Start AirPlay service in the background.
     */
    private fun startAirPlayService() {
        val serviceIntent = Intent(this, AirPlayService::class.java).apply {
            action = AirPlayService.ACTION_START
        }
        startForegroundService(serviceIntent)
    }

    /**
     * Transition to main screen with a smooth fade.
     */
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onBackPressed() {
        // Don't allow back during splash
    }
}

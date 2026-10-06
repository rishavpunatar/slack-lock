package com.slacklock

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Opaque full-screen cover drawn over a locked app, so its content doesn't flash
 * up while we work out the Gmail account or while the Home animation plays.
 *
 * It never takes touches or focus (the user can always navigate away, and Gmail
 * stays the active window for account checks), and it always removes itself
 * after [MAX_SHOW_MS] so it can't get stuck on screen.
 */
class Curtain(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var view: View? = null

    /** Shows the cover (or keeps it up), cancelling any pending [hideAfter]. */
    fun show() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, MAX_SHOW_MS)
        if (view != null) return
        val cover = TextView(service).apply {
            setBackgroundColor(service.getColor(R.color.bg))
            setTextColor(service.getColor(R.color.fg_dim))
            text = service.getString(R.string.app_name)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        try {
            windowManager.addView(cover, params)
            view = cover
        } catch (e: RuntimeException) {
            // Overlay refused (e.g. service disconnecting): enforcement still works, just without the cover.
        }
    }

    /** Keeps the cover up a little longer, e.g. until the Home animation has finished. */
    fun hideAfter(delayMs: Long) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delayMs)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private companion object {
        const val MAX_SHOW_MS = 4000L
    }
}

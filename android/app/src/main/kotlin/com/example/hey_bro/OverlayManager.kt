package com.example.hey_bro

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Manages a floating overlay pill that shows the assistant's current state.
 * Only visible when the user has granted SYSTEM_ALERT_WINDOW permission.
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private val mainHandler      = Handler(Looper.getMainLooper())
    private val windowManager    = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var statusText:  TextView?  = null
    private var statusIcon:  ImageView? = null
    private var isShowing    = false
    private var hideRunnable: Runnable? = null
    private var cachedLayoutParams: WindowManager.LayoutParams? = null
    private var cachedBackground: GradientDrawable? = null
    @Volatile private var updateSequence = 0L

    enum class State { LISTENING, PROCESSING, PLAYING }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API (thread-safe – all UI ops dispatched to main thread)
    // ─────────────────────────────────────────────────────────────────────────

    fun show(state: State, label: String = "") {
        if (!canDrawOverlays()) return
        val sequence = synchronized(this) { ++updateSequence }
        mainHandler.post {
            if (sequence == updateSequence) _show(state, label)
        }
    }

    fun hide() {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        val sequence = synchronized(this) { ++updateSequence }
        mainHandler.post {
            if (sequence == updateSequence) _hide()
        }
    }

    fun hideDelayed(delayMs: Long = 1500) {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        val sequence = synchronized(this) { ++updateSequence }
        hideRunnable = Runnable {
            if (sequence == updateSequence) _hide()
        }
        mainHandler.postDelayed(hideRunnable!!, delayMs)
    }

    fun destroy() {
        hide()
        overlayView = null
        statusText  = null
        statusIcon  = null
        cachedLayoutParams = null
        cachedBackground = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private
    // ─────────────────────────────────────────────────────────────────────────

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true

    private fun _show(state: State, label: String) {
        if (overlayView == null) createView()

        val (iconRes, bgColor, text) = when (state) {
            State.LISTENING  -> Triple(android.R.drawable.ic_btn_speak_now,  0xFF00BFA5.toInt(), label.ifBlank { "Listening…"  })
            State.PROCESSING -> Triple(android.R.drawable.ic_popup_sync,      0xFFF57C00.toInt(), label.ifBlank { "Processing…" })
            State.PLAYING    -> Triple(android.R.drawable.ic_media_play,       0xFF1565C0.toInt(), label.ifBlank { "Playing…"    })
        }

        // Reuse cached GradientDrawable instead of creating a new one every time
        val bg = cachedBackground ?: GradientDrawable().apply {
            shape   = GradientDrawable.RECTANGLE
            cornerRadius = 200f
        }.also { cachedBackground = it }
        bg.setColor(bgColor)
        overlayView?.background = bg
        statusIcon?.setImageResource(iconRes)
        statusText?.text = text

        // Reuse cached LayoutParams
        val params = cachedLayoutParams ?: buildLayoutParams().also { cachedLayoutParams = it }

        if (!isShowing) {
            try {
                windowManager.addView(overlayView, params)
                isShowing = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay: ${e.message}")
            }
        } else {
            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update overlay: ${e.message}")
            }
        }
    }

    private fun _hide() {
        if (isShowing && overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay: ${e.message}")
            }
            isShowing = false
        }
    }

    private fun createView() {
        // Container pill
        val pill = FrameLayout(context).apply {
            setPadding(48, 24, 48, 24)
        }

        // Row layout inside the pill
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        statusIcon = ImageView(context).apply {
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(56, 56).also { it.marginEnd = 16 }
        }

        statusText = TextView(context).apply {
            textSize  = 15f
            setTextColor(Color.WHITE)
        }

        row.addView(statusIcon)
        row.addView(statusText)
        pill.addView(row)
        overlayView = pill
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            it.y       = 160
        }
    }
}

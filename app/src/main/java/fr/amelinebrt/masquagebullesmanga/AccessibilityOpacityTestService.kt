package fr.amelinebrt.masquagebullesmanga

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Diagnostic only: tests an opaque white window using Android's trusted
 * accessibility-overlay window type, independent of the regular app overlay.
 */
class AccessibilityOpacityTestService : AccessibilityService() {
    companion object {
        const val ACTION_TEST_OPACITY =
            "fr.amelinebrt.masquagebullesmanga.ACCESSIBILITY_TEST_OPACITY"
    }

    private var windowManager: WindowManager? = null
    private var testView: View? = null
    private var receiverRegistered = false

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TEST_OPACITY) showOpaqueTest()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter(ACTION_TEST_OPACITY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(testReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(testReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun showOpaqueTest() {
        Handler(Looper.getMainLooper()).postDelayed({
            val wm = windowManager ?: return@postDelayed
            try {
                testView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
                val view = View(this).apply {
                    setBackgroundColor(Color.WHITE)
                    alpha = 1f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    alpha = 1f
                }
                wm.addView(view, params)
                testView = view
                view.postDelayed({
                    try { wm.removeView(view) } catch (_: Exception) {}
                    if (testView === view) testView = null
                }, 1800L)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Test de superposition avancée impossible : ${e.message ?: "erreur"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 3000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(testReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        try { testView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        testView = null
        windowManager = null
        super.onDestroy()
    }
}
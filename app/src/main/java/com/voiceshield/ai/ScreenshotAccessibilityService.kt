package com.voiceshield.ai


import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Whether our accessibility service is currently enabled by the user.
 *
 * Reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and delegates the parsing to the pure
 * [accessibilityServiceEnabledIn], so the fragile colon-separated-string handling is unit-tested.
 */
fun isScreenshotAccessibilityServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, ScreenshotAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    return accessibilityServiceEnabledIn(
        enabled,
        component.flattenToString(),
        component.flattenToShortString()
    )
}

/**
 * Optional, developer-only screen frame source.
 *
 * When the hidden "NGUỒN KHUNG HÌNH" switch selects the accessibility backend, the capture loop
 * pulls frames from here instead of a `MediaProjection`. Because
 * [AccessibilityService.takeScreenshot] does not use `MediaProjection`, the device's single
 * screen-capture slot stays free and the system screen recorder works at the same time.
 *
 * The service performs **no** event handling and reads **no** window content; it exists solely to
 * be a screenshot provider, and only while protection is active ([ProtectionState.isActive]).
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "accessibility-shot").apply { isDaemon = true }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Screenshot accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "Screenshot accessibility service disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        executor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Takes one screenshot and hands back a software `Bitmap`, or null on failure / when
     * protection is off. The callback runs on the caller's executor; the caller re-posts.
     */
    fun capture(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        // Never grab the screen when protection is off — this service may still be enabled.
        if (!ProtectionState.isActive(this)) {
            onResult(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    runCatching { buffer.close() }
                    onResult(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed: $errorCode")
                    onResult(null)
                }
            }
        )
    }

    companion object {
        private const val TAG = "SafeCallsCT-Accessibility"

        @Volatile
        private var instance: ScreenshotAccessibilityService? = null

        /** True while the user has the service enabled and it is connected. */
        fun isRunning(): Boolean = instance != null

        /**
         * Requests one screenshot through the connected service.
         *
         * @return false when the service is not connected, so the caller can surface a clear
         *   "enable the accessibility service" status instead of silently doing nothing.
         */
        fun capture(onResult: (Bitmap?) -> Unit): Boolean {
            val service = instance ?: return false
            service.capture(onResult)
            return true
        }
    }
}

package com.pcdeni.aicallerid.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.pcdeni.aicallerid.App
import com.pcdeni.aicallerid.R
import com.pcdeni.aicallerid.model.CallerIntel
import com.pcdeni.aicallerid.model.SourceRef
import com.pcdeni.aicallerid.notify.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class OverlayService : Service() {

    companion object {
        const val ACTION_INCOMING = "com.pcdeni.aicallerid.INCOMING"
        const val ACTION_SCAN_NOW = "com.pcdeni.aicallerid.SCAN_NOW"
        const val ACTION_PREVIEW = "com.pcdeni.aicallerid.PREVIEW"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_AUTO = "auto"

        private const val TAG = "OverlayService"
        private const val AUTO_DISMISS_MS = 45_000L
        // Below the system heads-up incoming-call notification, which draws above app overlays.
        private const val TOP_OFFSET_DP = 220f

        fun startIncoming(context: Context, number: String, auto: Boolean) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_INCOMING)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_AUTO, auto)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // Background start restrictions: never crash the caller (telephony path).
                Log.e(TAG, "Unable to start overlay service", e)
            }
        }

        fun startPreview(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_PREVIEW)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Unable to start overlay service", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dismissHandler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }
    private var controller: OverlayViewController? = null
    private var attached = false
    private var lookupJob: Job? = null
    private var currentNumber: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)
                val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
                if (number.isNullOrBlank()) stopIfIdle() else handleIncoming(number, auto)
            }
            ACTION_SCAN_NOW -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)
                if (number.isNullOrBlank()) stopIfIdle() else handleScanNow(number)
            }
            ACTION_PREVIEW -> handlePreview()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lookupJob?.cancel()
        scope.cancel()
        removeCard()
        controller = null
        super.onDestroy()
    }

    private fun handleIncoming(number: String, auto: Boolean) {
        currentNumber = number
        if (!Settings.canDrawOverlays(this)) {
            if (auto) {
                runLookupToNotification(number)
            } else {
                NotificationHelper.postScanPrompt(this, number)
                stopSelf()
            }
            return
        }
        if (auto) startScan(number) else showPrompt(number)
    }

    private fun handleScanNow(number: String) {
        currentNumber = number
        if (!Settings.canDrawOverlays(this)) {
            if (!App.instance.prefs.appUnlockedStub) {
                NotificationHelper.postError(this, number, getString(R.string.error_locked))
                stopSelf()
                return
            }
            runLookupToNotification(number)
            return
        }
        startScan(number)
    }

    private fun handlePreview() {
        val intel = previewIntel()
        currentNumber = intel.phoneNumber
        if (!Settings.canDrawOverlays(this)) {
            NotificationHelper.postIntel(this, intel)
            stopSelf()
            return
        }
        lookupJob?.cancel()
        ensureController().showResult(intel)
        resetDismissTimer()
    }

    private fun showPrompt(number: String) {
        lookupJob?.cancel()
        ensureController().showPrompt(number)
        resetDismissTimer()
    }

    private fun startScan(number: String) {
        lookupJob?.cancel()
        if (!App.instance.prefs.appUnlockedStub) {
            ensureController().showError(number, getString(R.string.error_locked))
            resetDismissTimer()
            return
        }
        ensureController().showScanning(number)
        resetDismissTimer()
        lookupJob = scope.launch {
            val result = App.instance.repository.lookup(number)
            result.fold(
                onSuccess = { controller?.showResult(it) },
                onFailure = { controller?.showError(number, errorText(it)) }
            )
            resetDismissTimer()
        }
    }

    private fun runLookupToNotification(number: String) {
        lookupJob?.cancel()
        lookupJob = scope.launch {
            val result = App.instance.repository.lookup(number)
            result.fold(
                onSuccess = { NotificationHelper.postIntel(this@OverlayService, it) },
                onFailure = {
                    NotificationHelper.postError(this@OverlayService, number, errorText(it))
                }
            )
            stopSelf()
        }
    }

    private fun ensureController(): OverlayViewController {
        val existing = controller
        if (existing != null) {
            if (!attached) attach(existing)
            return existing
        }
        val created = OverlayViewController(
            context = this,
            onScan = { currentNumber?.let(::startScan) },
            onRetry = { currentNumber?.let(::startScan) },
            onDismiss = { dismiss() }
        )
        controller = created
        attach(created)
        return created
    }

    private fun attach(viewController: OverlayViewController) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val margin = resources.getDimensionPixelSize(R.dimen.overlay_margin)
        val width = resources.displayMetrics.widthPixels - 2 * margin
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (TOP_OFFSET_DP * resources.displayMetrics.density).roundToInt()
            // No window animations: e-ink friendliness.
            windowAnimations = 0
        }
        try {
            windowManager.addView(viewController.root, params)
            attached = true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to attach overlay view", e)
        }
    }

    private fun dismiss() {
        removeCard()
        stopSelf()
    }

    private fun removeCard() {
        dismissHandler.removeCallbacks(dismissRunnable)
        if (!attached) return
        attached = false
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        controller?.let {
            try {
                windowManager.removeView(it.root)
            } catch (e: IllegalArgumentException) {
                // View was already removed; nothing to do.
                Log.e(TAG, "Overlay view already removed", e)
            }
        }
    }

    private fun resetDismissTimer() {
        dismissHandler.removeCallbacks(dismissRunnable)
        dismissHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    private fun stopIfIdle() {
        if (!attached && lookupJob?.isActive != true) stopSelf()
    }

    private fun errorText(error: Throwable?): String {
        val message = error?.message
        return when {
            message != null && message.contains("API key missing") ->
                getString(R.string.error_no_key)
            !message.isNullOrBlank() -> message
            else -> getString(R.string.error_network)
        }
    }

    private fun previewIntel(): CallerIntel = CallerIntel(
        phoneNumber = "+1 (800) 555-0142",
        callerName = "Northwind Cable Support",
        callerCategory = "Business",
        riskLevel = "Low Risk",
        isSpam = false,
        spamScore = 20,
        location = "Denver, CO, USA",
        conciseSummary = "Regional cable provider support line, commonly used for scheduled " +
            "service callbacks and appointment confirmations.",
        keyHighlights = listOf(
            "Registered business support line",
            "Callback number listed on official site",
            "Low spam report volume"
        ),
        recommendedAction = "Answer",
        eInkText = "NORTHWIND CABLE - SUPPORT - LOW RISK",
        sources = listOf(SourceRef("Northwind Cable", "https://example.com/northwind")),
        grounded = false,
        timestampMs = System.currentTimeMillis(),
        fromCache = false
    )
}

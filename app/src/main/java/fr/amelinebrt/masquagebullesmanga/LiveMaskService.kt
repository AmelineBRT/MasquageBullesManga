package fr.amelinebrt.masquagebullesmanga

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Experimental live reader overlay. Screen frames are processed locally and only
 * the white transparent mask is drawn over the current manga app.
 */
class LiveMaskService : Service() {
    companion object {
        const val ACTION_STOP = "fr.amelinebrt.masquagebullesmanga.STOP_LIVE_MASK"
        const val ACTION_TEST_OPACITY = "fr.amelinebrt.masquagebullesmanga.TEST_OVERLAY_OPACITY"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "live_mask_channel"
        private const val NOTIFICATION_ID = 501
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: ImageView? = null
    private var windowManager: WindowManager? = null
    private var handlerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processing = AtomicBoolean(false)
    private var latestFrame: Bitmap? = null
    private var lastFrameHash: Long = Long.MIN_VALUE
    private var frameVersion = 0L
    private var lastSampleAt = 0L
    private var lastOverlayBitmap: Bitmap? = null

    private val detectRunnable: Runnable = Runnable {
        val snapshot = synchronized(this) {
            Pair(latestFrame?.copy(Bitmap.Config.ARGB_8888, false), frameVersion)
        }
        val frame = snapshot.first ?: return@Runnable
        val analysedVersion = snapshot.second
        if (!processing.compareAndSet(false, true)) {
            frame.recycle()
            return@Runnable
        }
        scope.launch {
            try {
                val result = BubbleMaskDetector.run(this@LiveMaskService, frame)
                withContext(Dispatchers.Main) {
                    val oldOverlay = lastOverlayBitmap
                    overlayView?.setImageBitmap(result.overlayBitmap)
                    lastOverlayBitmap = result.overlayBitmap
                    if (oldOverlay != null && oldOverlay !== result.overlayBitmap && !oldOverlay.isRecycled) {
                        oldOverlay.recycle()
                    }
                }
                // The preview and source are no longer needed after the overlay is set.
                result.bitmap.recycle()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LiveMaskService,
                        "Masquage en direct : ${e.message ?: "erreur de détection"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                frame.recycle()
                processing.set(false)
                val newerFrameAvailable = synchronized(this@LiveMaskService) {
                    frameVersion != analysedVersion
                }
                if (newerFrameAvailable) {
                    workerHandler?.removeCallbacks(detectRunnable)
                    workerHandler?.postDelayed(detectRunnable, 450L)
                }
            }
        }
    }

    private val imageListener: ImageReader.OnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < 250L) {
            val skipped = reader.acquireLatestImage()
            skipped?.close()
            return@OnImageAvailableListener
        }
        lastSampleAt = now
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            val bitmap = image.toBitmap()
            val hash = sampledHash(bitmap)
            val changed = hash != lastFrameHash
            if (changed) {
                lastFrameHash = hash
                synchronized(this) {
                    latestFrame?.recycle()
                    latestFrame = bitmap
                    frameVersion++
                }
                // Wait for scrolling to stop briefly, so the mask is not drawn over
                // a different frame than the one that was analysed.
                workerHandler?.removeCallbacks(detectRunnable)
                workerHandler?.postDelayed(detectRunnable, 450L)
            } else {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            // A transient frame can be incomplete; the next screen frame will retry.
        } finally {
            image.close()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TEST_OPACITY) {
            sendBroadcast(
                Intent(AccessibilityOpacityTestService.ACTION_TEST_OPACITY)
                    .setPackage(packageName)
            )
            Toast.makeText(
                this,
                "Test avancé envoyé. Vérifie que le service d'accessibilité de test est activé.",
                Toast.LENGTH_LONG
            ).show()
            return START_STICKY
        }
        if (projection != null) return START_STICKY

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startCapture(resultCode, resultData)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible de démarrer la capture : ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val width = max(1, metrics.widthPixels)
        val height = max(1, metrics.heightPixels)
        val density = metrics.densityDpi

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            // Do not let view-level alpha reduce the opacity of the white mask.
            alpha = 1f
            imageAlpha = 255
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
        overlayView = image
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Match MangaLensFR's known-good overlay setup: the window itself
            // must be fully opaque; transparency comes only from bitmap pixels.
            alpha = 1f
        }
        windowManager?.addView(image, overlayParams)

        handlerThread = HandlerThread("LiveMaskCapture").also { it.start() }
        workerHandler = Handler(handlerThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also {
            it.setOnImageAvailableListener(imageListener, workerHandler)
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData).also { mediaProjection ->
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, workerHandler)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "MasquageBullesManga",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                workerHandler
            )
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun sampledHash(bitmap: Bitmap): Long {
        // A lightweight visual-change check to debounce scrolling without comparing
        // every pixel of every captured frame.
        var hash = 1125899906842597L
        val xStep = max(1, bitmap.width / 24)
        val yStep = max(1, bitmap.height / 32)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                hash = hash * 31 + bitmap.getPixel(x, y)
                x += xStep
            }
            y += yStep
        }
        return hash
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Masquage en direct",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LiveMaskService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val testIntent = Intent(this, LiveMaskService::class.java).setAction(ACTION_TEST_OPACITY)
        val testPendingIntent = PendingIntent.getService(
            this, 2, testIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Masquage Bulles Manga actif")
            .setContentText("Le masquage se met à jour après le défilement.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                    "Tester le blanc opaque",
                    testPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Arrêter",
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        workerHandler?.removeCallbacksAndMessages(null)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        synchronized(this) {
            latestFrame?.recycle()
            latestFrame = null
        }
        lastOverlayBitmap?.let { if (!it.isRecycled) it.recycle() }
        lastOverlayBitmap = null
        handlerThread?.quitSafely()
        scope.cancel()
        super.onDestroy()
    }
}

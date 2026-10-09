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
    private var lastResultAt = 0L

    private val detectRunnable = Runnable {
        val frame = synchronized(this) { latestFrame?.copy(Bitmap.Config.ARGB_8888, false) }
            ?: return@Runnable
        if (!processing.compareAndSet(false, true)) {
            frame.recycle()
            return@Runnable
        }
        scope.launch {
            try {
                val result = BubbleMaskDetector.run(this@LiveMaskService, frame)
                withContext(Dispatchers.Main) {
                    overlayView?.setImageBitmap(result.overlayBitmap)
                    lastResultAt = System.currentTimeMillis()
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
            }
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
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
            .addAction(Notification.Action.Builder(null, "Arrêter", stopPendingIntent).build())
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
        handlerThread?.quitSafely()
        scope.cancel()
        super.onDestroy()
    }
}

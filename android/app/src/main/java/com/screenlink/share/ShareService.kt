package com.screenlink.share

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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max

class ShareService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_CONTROL = "control"
        private const val CH_ID = "screenlink"
        private const val NOTIF_ID = 42
        private const val TAG = "ScreenLink"
        private const val CONTROL_PORT = 8081
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var audioRecord: AudioRecord? = null
    private var controlServer: ControlServer? = null
    private val running = AtomicBoolean(false)
    private var hostBase = ""
    private var jpegQuality = 40
    private var scale = 0.32f
    private var wantAudio = false
    private var wantControl = true
    private var sessionToken = ""
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val frameSenderRunning = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                hostBase = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                sessionToken = try {
                    val u = URL(hostBase)
                    val q = u.query ?: ""
                    q.split("&").mapNotNull { part ->
                        val kv = part.split("=", limit = 2)
                        if (kv.size == 2 && kv[0] == "t") kv[1] else null
                    }.firstOrNull() ?: ""
                } catch (_: Exception) { "" }
                hostBase = hostBase.substringBefore("?").trimEnd('/')
                wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
                wantControl = intent.getBooleanExtra(EXTRA_CONTROL, true)
                when (intent.getIntExtra(EXTRA_QUALITY, 0)) {
                    1 -> { scale = 0.45f; jpegQuality = 50 }
                    2 -> { scale = 0.55f; jpegQuality = 60 }
                    3 -> { scale = 0.75f; jpegQuality = 75 }
                    4 -> { scale = 1.00f; jpegQuality = 85 }
                    else -> { scale = 0.32f; jpegQuality = 35 }
                }
                if (data != null) startShare(code, data)
            }
            ACTION_STOP -> stopShare()
        }
        return START_STICKY
    }

    private fun startShare(resultCode: Int, data: Intent) {
        if (running.get()) return
        startForeground(NOTIF_ID, buildNotification())

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        if (projection == null) { stopSelf(); return }

        if (wantControl) {
            controlServer = ControlServer(CONTROL_PORT, sessionToken).also { it.start() }
            registerControlEndpoint()
        }

        handlerThread = HandlerThread("capture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = max(240, (metrics.widthPixels * scale).toInt())
        val h = max(400, (metrics.heightPixels * scale).toInt())
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection!!.createVirtualDisplay(
            "screenlink", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        running.set(true)
        imageReader!!.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bmp = imageToBitmap(image, w, h) ?: return@setOnImageAvailableListener
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                bmp.recycle()
                val bytes = baos.toByteArray()
                latestFrame.set(bytes)
                ensureFrameSender()
            } catch (e: Exception) {
                Log.w(TAG, "frame", e)
            } finally {
                image?.close()
            }
        }, handler)

        if (wantAudio && Build.VERSION.SDK_INT >= 29) startAudioCapture()
    }

    private fun localIp(): String? {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) return null
            return String.format(
                "%d.%d.%d.%d",
                ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun registerControlEndpoint() {
        val ip = localIp() ?: return
        val controlUrl = "http://$ip:$CONTROL_PORT"
        thread {
            try {
                val body = """{"control":"$controlUrl","token":"$sessionToken"}""".toByteArray()
                val url = URL("$hostBase/register")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (sessionToken.isNotEmpty()) {
                        setRequestProperty("X-ScreenLink-Token", sessionToken)
                    }
                    setFixedLengthStreamingMode(body.size)
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val respText = try { c.inputStream.bufferedReader().readText() } catch (_: Exception) {
                    try { c.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "" }
                } ?: ""
                Log.i(TAG, "Registered control $controlUrl → $code $respText")
                if (code in 200..299 && respText.isNotBlank()) {
                    try {
                        val jo = org.json.JSONObject(respText)
                        val newTok = jo.optString("token", "")
                        if (newTok.isNotEmpty()) {
                            sessionToken = newTok
                            Log.i(TAG, "Session token rotated after register")
                            controlServer?.updateToken(sessionToken)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "register body parse: ${e.message}")
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "register failed: ${e.message}")
            }
        }
    }

    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap? {
        val plane = image.planes[0]
        val buf = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buf)
        if (rowPadding == 0) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, w, h)
        bitmap.recycle()
        return cropped
    }

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val proj = projection ?: return
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val sampleRate = 16000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            val format = AudioFormat.Builder()
                .setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(channel).build()
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            audioRecord?.startRecording()
            thread(name = "audio-cap") {
                val buf = ByteArray(minBuf)
                while (running.get()) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: break
                    if (n > 0) postBytes("/pcm", "application/octet-stream", buf.copyOf(n))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio capture failed", e)
        }
    }

    private fun ensureFrameSender() {
        if (frameSenderRunning.getAndSet(true)) return
        thread(name = "frame-sender") {
            try {
                while (running.get()) {
                    val bytes = latestFrame.getAndSet(null)
                    if (bytes == null) {
                        try { Thread.sleep(15) } catch (_: Exception) {}
                        continue
                    }
                    postBytes("/frame", "image/jpeg", bytes)
                }
            } finally {
                frameSenderRunning.set(false)
            }
        }
    }

    private fun postBytes(path: String, mime: String, body: ByteArray) {
        try {
            val c = (URL(hostBase + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Content-Type", mime)
                if (sessionToken.isNotEmpty()) {
                    setRequestProperty("X-ScreenLink-Token", sessionToken)
                }
                setFixedLengthStreamingMode(body.size)
            }
            c.outputStream.use { out: OutputStream -> out.write(body) }
            c.responseCode
            val next = c.getHeaderField("X-ScreenLink-Token")
            if (!next.isNullOrBlank() && next != sessionToken) {
                sessionToken = next
                Log.i(TAG, "Token updated from response ($path)")
                try { controlServer?.updateToken(sessionToken) } catch (e: Exception) {
                    Log.w(TAG, "control token update: ${e.message}")
                }
            }
            c.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "post $path: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_ID, "ScreenLink", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun stopShare() {
        running.set(false)
        controlServer?.stop()
        controlServer = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopShare()
        super.onDestroy()
    }
}

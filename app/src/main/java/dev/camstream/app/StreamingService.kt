package dev.camstream.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the camera capture pipeline and speaks the DroidCam wire
 * protocol described in PROTOCOL.md: a phone-side TCP server on port 4747 that, per client,
 * writes a 9-byte header followed by an endless stream of length-prefixed JPEG frames.
 */
class StreamingService : LifecycleService() {

    companion object {
        const val PORT = 4747
        const val ACTION_START = "dev.camstream.app.action.START"
        const val ACTION_STOP = "dev.camstream.app.action.STOP"
        const val ACTION_SWITCH_CAMERA = "dev.camstream.app.action.SWITCH_CAMERA"
        private const val CHANNEL_ID = "camstream_service"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "StreamingService"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isFrontCamera: Boolean = false
            private set
    }

    private val frameHolder = LatestFrameHolder()
    private var cameraCapture: CameraCapture? = null
    private var serverSocket: ServerSocket? = null
    private val serverExecutor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SWITCH_CAMERA -> {
                switchCamera()
                return START_STICKY
            }
        }
        startStreaming()
        return START_STICKY
    }

    /** Toggles between back and front camera; a no-op if not currently streaming. */
    private fun switchCamera() {
        val capture = cameraCapture ?: return
        capture.switchCamera()
        isFrontCamera = capture.isFrontCamera
    }

    private fun startStreaming() {
        if (running.getAndSet(true)) return
        isRunning = true

        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        isFrontCamera = false
        cameraCapture = CameraCapture(applicationContext, frameHolder).also { it.start(this) }
        serverExecutor.execute { runServer() }
    }

    private fun stopStreaming() {
        if (!running.getAndSet(false)) return
        isRunning = false
        isFrontCamera = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        cameraCapture?.stop()
        cameraCapture = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun runServer() {
        try {
            ServerSocket(PORT).use { socket ->
                serverSocket = socket
                Log.i(TAG, "Listening on port $PORT")
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        if (running.get()) Log.w(TAG, "accept() failed", e)
                        break
                    }
                    serverExecutor.execute { handleClient(client) }
                }
            }
        } catch (e: IOException) {
            // Most commonly EADDRINUSE (something else already bound to the port, e.g. the
            // official DroidCam app still running). Without this, the service would be left
            // claiming "running" (camera open, notification shown) with nothing actually
            // listening, and every future Start tap would silently no-op on the running guard.
            Log.e(TAG, "Server socket failed", e)
            mainHandler.post { stopStreaming() }
        }
    }

    private fun handleClient(client: Socket) {
        Log.i(TAG, "Client connected: ${client.inetAddress?.hostAddress}")
        var frameVersion = 0L
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = BufferedOutputStream(client.getOutputStream())

            // Read the first request line (e.g. "CMD /v3/video/jpg/640x480"). We don't need
            // to parse it beyond confirming a request arrived; see PROTOCOL.md: any later
            // control commands sent on the same socket are deliberately left unread.
            readRequestLine(client, input)

            val capture = waitForCapture() ?: return
            writeHeader(output, capture.frameWidth, capture.frameHeight)
            output.flush()

            while (running.get() && !client.isClosed) {
                val (jpeg, version) = frameHolder.awaitNext(frameVersion)
                frameVersion = version
                writeFrame(output, jpeg)
                output.flush()
            }
        } catch (e: IOException) {
            Log.i(TAG, "Client disconnected: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun waitForCapture(): CameraCapture? {
        while (running.get()) {
            val capture = cameraCapture
            if (capture != null && capture.frameWidth > 0) return capture
            Thread.sleep(20)
        }
        return null
    }

    /**
     * Reads the initial "CMD /v3/video/..." request. Per PROTOCOL.md this line has **no
     * trailing newline**, so there's no delimiter to read up to. Waiting for one would just
     * block forever (until the PC's own SO_RCVTIMEO gives up and closes the connection first,
     * which is exactly the bug this replaced: it silently ate ~5s of every connection and then
     * lost the race to write a response at all). Instead, drain whatever the PC sends and stop
     * once a short read-timeout shows no more bytes are coming.
     */
    private fun readRequestLine(client: Socket, input: InputStream, maxLen: Int = 256) {
        val previousTimeout = client.soTimeout
        client.soTimeout = 300
        val buf = ByteArray(maxLen)
        var i = 0
        try {
            while (i < maxLen) {
                val b = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (b == -1) break
                buf[i++] = b.toByte()
            }
        } finally {
            client.soTimeout = previousTimeout
        }
        Log.d(TAG, "Request: ${String(buf, 0, i)}")
    }

    private fun writeHeader(output: OutputStream, width: Int, height: Int) {
        // 9-byte header: width (u16 BE), height (u16 BE), 5 reserved bytes. See PROTOCOL.md.
        val header = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
        header.putShort(width.toShort())
        header.putShort(height.toShort())
        header.put(ByteArray(5))
        output.write(header.array())
    }

    private fun writeFrame(output: OutputStream, jpeg: ByteArray) {
        // Length prefix is little-endian, unlike the header. Easy to mix up; see PROTOCOL.md.
        val lengthPrefix = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        lengthPrefix.putInt(jpeg.size)
        output.write(lengthPrefix.array())
        output.write(jpeg)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopStreaming()
        serverExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}

package dev.camstream.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Single-screen UI: shows the address to type into the PC client, a start/stop toggle, and
 * a front/back camera switch for [StreamingService]. Deliberately no camera preview here:
 * that would cost battery for no benefit, since this screen is control-only.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var switchCameraButton: MaterialButton
    private var addressValue: String? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            startStreamingService()
        } else {
            statusText.text = getString(R.string.status_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        toggleButton = findViewById(R.id.toggleButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)

        val ip = getLocalIpAddress()
        if (ip != null) {
            addressValue = "$ip:${StreamingService.PORT}"
            addressText.text = getString(R.string.address_format, ip, StreamingService.PORT)
        } else {
            addressText.text = getString(R.string.address_unknown)
        }

        toggleButton.setOnClickListener {
            if (StreamingService.isRunning) stopStreamingService() else requestNeededPermissionsThenStart()
        }

        switchCameraButton.setOnClickListener { switchCamera() }

        addressText.setOnClickListener { copyAddressToClipboard() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestNeededPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startStreamingService() else requestPermissions.launch(missing.toTypedArray())
    }

    private fun startStreamingService() {
        val intent = Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        refreshStatusSoon()
    }

    private fun stopStreamingService() {
        val intent = Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP)
        startService(intent)
        refreshStatusSoon()
    }

    private fun switchCamera() {
        if (!StreamingService.isRunning) return
        val intent = Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_SWITCH_CAMERA)
        startService(intent)
        refreshStatusSoon()
    }

    /**
     * [StreamingService.isRunning] flips asynchronously on the service side (after a binder
     * round-trip through onStartCommand), so an immediate read right after firing the intent
     * can still show the pre-change state. Refresh right away for responsiveness, then once
     * more shortly after so the UI catches up once the service has actually reacted; e.g. if
     * the port bind fails, isRunning snaps back to false.
     */
    private fun refreshStatusSoon() {
        refreshStatus()
        toggleButton.postDelayed({ refreshStatus() }, 400)
    }

    private fun refreshStatus() {
        val running = StreamingService.isRunning

        statusDot.setBackgroundResource(if (running) R.drawable.dot_active else R.drawable.dot_idle)
        statusText.text = getString(if (running) R.string.status_running else R.string.status_stopped)

        toggleButton.text = getString(if (running) R.string.action_stop else R.string.action_start)
        toggleButton.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        toggleButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.stop_action else R.color.brand_primary)
        )

        switchCameraButton.isEnabled = running
        switchCameraButton.text = getString(
            if (StreamingService.isFrontCamera) R.string.action_switch_to_back else R.string.action_switch_to_front
        )
    }

    private fun copyAddressToClipboard() {
        val value = addressValue ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), value))
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show()
    }

    /** Scans local network interfaces for the phone's LAN IPv4 address (WiFi or USB tethering). */
    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}

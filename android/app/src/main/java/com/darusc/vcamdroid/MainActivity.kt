package com.darusc.vcamdroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.darusc.vcamdroid.databinding.ActivityMainBinding
import com.darusc.vcamdroid.networking.ServerDiscovery
import com.darusc.vcamdroid.networking.ConnectionManager
import com.darusc.vcamdroid.util.Logger
import com.darusc.vcamdroid.video.Camera
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.Permission

class MainActivity : AppCompatActivity(), ConnectionManager.ConnectionStateCallback {

    private lateinit var viewBinding: ActivityMainBinding

    private val qrscanner = QRScanner()
    private var connectionManager = ConnectionManager.getInstance(this)
    private var camera: Camera? = null
    private var discovery: ServerDiscovery? = null
    @Volatile private var connectionEstablished = false
    private val discoveryScope = CoroutineScope(Dispatchers.IO)

    private var isConnecting = false

    private val TAG = "VCamdroid"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        viewBinding.logReportButton.setOnClickListener {
            val intent = Intent(this, LogActivity::class.java)
            startActivity(intent)
        }

        enableEdgeToEdge()
        initialize()
    }

    override fun onResume() {
        super.onResume()
        connectionManager = ConnectionManager.getInstance(this)
        if(camera != null) {
            camera!!.start(Size(1280, 720), CameraSelector.DEFAULT_BACK_CAMERA)
            // Upstream called connectWIFI() here unconditionally, which re-opened a
            // second connection and clobbered the live one. Only fall back to LAN
            // discovery when we are not already connected/connecting.
            if (!connectionEstablished) {
                connectWIFI()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode == 1000) {
            if(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initialize()
            } else {
                // If camera permission was not granted show a prompt to the user
                // to go to settings and enable the required permission

                MaterialAlertDialogBuilder(this)
                    .setTitle("Camera Permission Required")
                    .setMessage("Please enable camera permission in settings and restart the app.")
                    .setPositiveButton("Go to settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    override fun onConnectionSuccessful(connectionMode: ConnectionManager.Mode) {
        // Stop any further discovery the moment we have a live link: a late sweep hit
        // would open a second socket and replace this one in ConnectionManager.
        connectionEstablished = true
        discovery?.cancel()
        qrscanner.stop()
        Logger.log("MAIN", "Connection successful $connectionMode")

        val intent = Intent(this, StreamActivity::class.java)
        startActivity(intent)
    }

    override fun onConnectionFailed(connectionMode: ConnectionManager.Mode) {
        runOnUiThread {
            if (connectionMode == ConnectionManager.Mode.USB) {
                // Wired/ADB (loopback through the PC's adb forward) is unavailable —
                // fall back to zero-config LAN discovery. No QR code required.
                Logger.log("MAIN", "Loopback unavailable, starting LAN discovery")
                Toast.makeText(this, "Wired unavailable, looking for PC on this network…", Toast.LENGTH_SHORT).show()
                connectWIFI()
            } else {
                isConnecting = false
                qrscanner.stop()
                discovery?.cancel()
                Logger.log("MAIN", "Error: Cannot connect!")
                Toast.makeText(this, "No PC found on this network. Start the PC beacon or restart app", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Check for CAMERA permission and request it if not granted
     */
    private fun checkPermissions(): Boolean {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1000)
            return false
        }

        return true
    }

    /**
     * If the required permissions are grante create the camera instance,
     * start it, and initialize the connection procedure
     */
    private fun initialize() {
        if(checkPermissions()) {
            camera = Camera(
                viewBinding.viewFinder.surfaceProvider,
                ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888,
                ::processImage,
                this,
                this
            )
            camera!!.start(Size(1280, 720), CameraSelector.DEFAULT_BACK_CAMERA)

            // Always try loopback first (works whenever the PC has the adb forward),
            // and fall back to LAN discovery on failure. See connectUSB().
            connectUSB()
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        // Process each incoming image from the camera
        // launchScanTask() will scan and call the callback on success
        // only if start() was called before
        qrscanner.launchScanTask(imageProxy, camera?.screenRectToImageRect(viewBinding.overlay.rect, viewBinding.overlay.size) ?: Rect()) { result ->
            runOnUiThread {
                if(result != null) {
                    qrscanner.stop()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Connect via WiFi")
                        .setMessage("Connect to ${result.address}:${result.port}?")
                        .setPositiveButton("Connect") { _, _ ->
                            connectionManager.connect(result.address, result.port)
                        }
                        .setNegativeButton("Cancel") { _, _ -> qrscanner.start() }
                        .show()
                } else {
                    Logger.log("MAIN", "Invalid QR code")
                    Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hasUsbConnection(): Boolean {
        val intent = applicationContext.registerReceiver(
            null,
            IntentFilter("android.hardware.usb.action.USB_STATE")
        )
        return intent?.getBooleanExtra("connected", false) == true
    }

    private fun hasWifiConnection(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun connectUSB() {
        // Always attempt loopback: the PC's "adb forward tcp:6969 tcp:6969" makes the
        // server reachable at 127.0.0.1 whether or not a cable is present. Upstream
        // gated this on android.hardware.usb.action.USB_STATE, which reports the PHONE's
        // USB-host port (OTG), not the PC cable, so it never fired on a normal phone.
        connectionManager.connect(6969)
    }

    private fun connectWIFI() {
        // The phone finds the PC by itself: sweep its own subnet for a listener on the
        // server port. Nothing has to run on the PC beyond the VCamdroid window, and no
        // QR code has to be scanned.
        val discovery = ServerDiscovery(6969) { ip ->
            Logger.log("MAIN", "PC discovered at $ip")
            connectionManager.connect(ip, 6969)
        }
        this.discovery = discovery
        discoveryScope.launch { discovery.sweepSubnet() }
        // Optional fast path: only useful if the owner ever runs pc/beacon.py.
        discovery.listenForBeacon { ip ->
            Logger.log("MAIN", "PC beacon at $ip")
            connectionManager.connect(ip, 6969)
        }
        qrscanner.start()
    }
}
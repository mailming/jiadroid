package dev.jiadroid.follow

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.jiadroid.follow.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val link = AtomicReference<RobotClient?>(null)
    private var cameraProvider: ProcessCameraProvider? = null
    private var connecting = false
    private var ticking = false
    private var lastTickNs = 0L
    private var lastMarkerAt = 0L
    private var lastScene = Scene(false, 0f, 0f)
    private var markerCorners: List<PointF>? = null
    private var markerImageWidth = 0
    private var markerImageHeight = 0
    private var lastCommandKey: String? = null
    private var lastSendAt = 0L
    private var hfovRad = Math.toRadians(70.0)
    private var sensorHfov = 0.0
    private var sensorVfov = 0.0
    private val pose = Pose(0f, 0f, (Math.PI / 2.0).toFloat())
    private var gait = 0f

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.link.text = getString(R.string.camera_denied)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            val now = System.nanoTime()
            if (lastTickNs != 0L) {
                val dt = ((now - lastTickNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                step(dt)
            }
            lastTickNs = now
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.connect.setOnClickListener {
            if (link.get() != null) disconnect() else connectToLaptop()
        }
        binding.host.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                connectToLaptop()
                true
            } else {
                false
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        ticking = true
        lastTickNs = 0L
        handler.post(tick)
    }

    override fun onPause() {
        ticking = false
        handler.removeCallbacks(tick)
        send(decide(Scene(false, 0f, 0f)), force = true)
        super.onPause()
    }

    override fun onDestroy() {
        val robot = link.getAndSet(null)
        if (robot != null) release(robot)
        cameraProvider?.unbindAll()
        scanner.close()
        cameraExecutor.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    private fun step(dt: Float) {
        val fresh = lastScene.visible && SystemClock.elapsedRealtime() - lastMarkerAt < HOLD_MS
        val scene = if (fresh) lastScene else Scene(false, 0f, 0f)
        if (!fresh) {
            lastScene = scene
            markerCorners = null
            binding.overlay.setMarker(null, 0, 0)
        }
        val decision = decide(scene)
        if (decision.command == "STOP") gait = 0f else gait += dt * 7f
        stepPose(pose, decision.forward, decision.lateral, decision.yaw, dt)
        binding.duck.render(pose, scene, gait, hfovRad.toFloat())
        show(decision, scene)
        send(decision, force = false)
    }

    private fun show(decision: FollowDecision, scene: Scene) {
        val moving = decision.command != "STOP"
        binding.situation.text = decision.situation
        binding.command.text = decision.command
        binding.command.setTextColor(ContextCompat.getColor(this, if (moving) R.color.go else R.color.stop))
        val range = if (scene.visible) String.format(Locale.US, "%.2f m", scene.distance) else "no marker"
        val robot = link.get()
        val where = if (robot == null) "phone sim" else robot.safety
        binding.detail.text = String.format(
            Locale.US,
            "forward %.2f m/s   turn %.2f rad/s   head %.2f   %s   %s",
            decision.forward,
            decision.yaw,
            decision.headYaw,
            range,
            where,
        )
    }

    private fun send(decision: FollowDecision, force: Boolean) {
        val robot = link.get() ?: return
        val now = SystemClock.elapsedRealtime()
        val key = commandKey(decision)
        val changed = key != lastCommandKey
        val keepalive = decision.command != "STOP" && now - lastSendAt > 1000
        if (!force && !changed && !keepalive) return
        lastCommandKey = key
        lastSendAt = now
        io.execute {
            try {
                if (link.get() !== robot) return@execute
                if (decision.command == "STOP") robot.stop() else robot.walk(decision)
            } catch (error: RobotException) {
                handler.post { binding.link.text = error.message }
            } catch (error: RobotException) {
                handler.post { binding.link.text = error.message }
            } catch (error: Exception) {
                handler.post { onLinkFailed(robot, error) }
            }
        }
    }

    private fun commandKey(decision: FollowDecision): String {
        if (decision.command == "STOP") return "STOP"
        return decision.command + ":" + (decision.headYaw * 20f).roundToInt()
    }

    private fun connectToLaptop() {
        if (connecting || link.get() != null) return
        val endpoint = parseEndpoint(binding.host.text?.toString().orEmpty())
        if (endpoint == null) {
            binding.link.text = getString(R.string.need_address)
            return
        }
        hideKeyboard()
        connecting = true
        binding.connect.isEnabled = false
        binding.link.text = getString(R.string.connecting)
        val (host, port) = endpoint
        io.execute {
            try {
                val robot = RobotClient.connect(host, port)
                handler.post {
                    connecting = false
                    binding.connect.isEnabled = true
                    link.set(robot)
                    lastCommandKey = null
                    binding.connect.text = getString(R.string.disconnect)
                    binding.link.text = getString(R.string.connected, robot.name, robot.servoCount, host, port)
                }
            } catch (error: Exception) {
                handler.post {
                    connecting = false
                    binding.connect.isEnabled = true
                    binding.link.text = error.message ?: getString(R.string.need_address)
                }
            }
        }
    }

    private fun disconnect() {
        val robot = link.getAndSet(null)
        lastCommandKey = null
        binding.connect.text = getString(R.string.connect)
        binding.link.text = getString(R.string.link_idle)
        if (robot != null) release(robot)
    }

    private fun release(robot: RobotClient) {
        io.execute {
            try {
                robot.stop()
            } catch (_: Exception) {
            }
            robot.close()
        }
    }

    private fun onLinkFailed(robot: RobotClient, error: Exception) {
        if (!link.compareAndSet(robot, null)) return
        lastCommandKey = null
        binding.connect.text = getString(R.string.connect)
        binding.link.text = error.message ?: getString(R.string.link_idle)
        release(robot)
        Log.i(TAG, "simulator link closed", error)
    }

    private fun parseEndpoint(text: String): Pair<String, Int>? {
        val value = text.trim()
        if (value.isEmpty()) return null
        val colon = value.lastIndexOf(':')
        if (colon > 0 && value.substring(colon + 1).all { it.isDigit() }) {
            val port = value.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val host = value.substring(0, colon)
            if (host.isEmpty()) return null
            return host to port
        }
        return value to 8765
    }

    private fun markerWidthM(): Float {
        val mm = binding.markerMm.text?.toString()?.toFloatOrNull() ?: return MARKER_WIDTH_MM / 1000f
        if (mm < 10f || mm > 300f) return MARKER_WIDTH_MM / 1000f
        return mm / 1000f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.host.windowToken, 0)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindCamera(provider)
            } catch (error: Exception) {
                binding.situation.text = error.message ?: getString(R.string.no_back_camera)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            binding.situation.text = getString(R.string.no_back_camera)
            return
        }
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(selector).build()
        preview.surfaceProvider = binding.preview.surfaceProvider
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(cameraExecutor, ::analyze)
        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            readHfov(camera)
        } catch (error: Exception) {
            binding.situation.text = error.message ?: getString(R.string.no_back_camera)
            Log.i(TAG, "camera bind failed", error)
        }
    }

    private fun analyze(image: androidx.camera.core.ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val uprightWidth = if (rotation == 90 || rotation == 270) image.height else image.width
        val uprightHeight = if (rotation == 90 || rotation == 270) image.width else image.height
        try {
            val input = InputImage.fromMediaImage(media, rotation)
            scanner.process(input)
                .addOnSuccessListener { codes ->
                    val hit = codes.firstOrNull { it.rawValue == MARKER_PAYLOAD } ?: return@addOnSuccessListener
                    val corners = cornersOf(hit) ?: return@addOnSuccessListener
                    handler.post {
                        if (isDestroyed) return@post
                        onMarker(corners, uprightWidth, uprightHeight, rotation)
                    }
                }
                .addOnCompleteListener { image.close() }
        } catch (error: Exception) {
            image.close()
            Log.i(TAG, "marker scan failed", error)
        }
    }

    private fun onMarker(corners: List<PointF>, imageWidth: Int, imageHeight: Int, rotation: Int) {
        val centerX = corners.map { it.x }.average().toFloat()
        val top = hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y)
        val bottom = hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y)
        hfovRad = hfovFor(rotation)
        val measured = measure(centerX, (top + bottom) / 2f, imageWidth, hfovRad, markerWidthM())
        if (!measured.visible) return
        lastScene = smoothScene(lastScene, measured)
        lastMarkerAt = SystemClock.elapsedRealtime()
        markerCorners = corners
        markerImageWidth = imageWidth
        markerImageHeight = imageHeight
        binding.overlay.setMarker(corners, imageWidth, imageHeight)
    }

    private fun cornersOf(barcode: Barcode): List<PointF>? {
        val points = barcode.cornerPoints
        if (points != null && points.size >= 4) {
            return points.take(4).map { PointF(it.x.toFloat(), it.y.toFloat()) }
        }
        val box = barcode.boundingBox ?: return null
        return listOf(
            PointF(box.left.toFloat(), box.top.toFloat()),
            PointF(box.right.toFloat(), box.top.toFloat()),
            PointF(box.right.toFloat(), box.bottom.toFloat()),
            PointF(box.left.toFloat(), box.bottom.toFloat()),
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun readHfov(camera: Camera) {
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val focal = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val physical = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixels = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (focal == null || focal.isEmpty() || physical == null || pixels == null || active == null) return
            if (pixels.width == 0 || pixels.height == 0 || focal[0] <= 0f) return
            val widthMm = active.width() * (physical.width / pixels.width.toFloat())
            val heightMm = active.height() * (physical.height / pixels.height.toFloat())
            val focalMm = focal[0].toDouble()
            val horizontal = 2.0 * atan((widthMm / 2.0) / focalMm)
            val vertical = 2.0 * atan((heightMm / 2.0) / focalMm)
            if (horizontal < Math.toRadians(30.0) || horizontal > Math.toRadians(120.0)) return
            sensorHfov = horizontal
            sensorVfov = vertical
            Log.i(TAG, "camera hfov ${Math.toDegrees(horizontal)} vfov ${Math.toDegrees(vertical)}")
        } catch (error: Exception) {
            Log.i(TAG, "camera fov unavailable", error)
        }
    }

    private fun hfovFor(rotation: Int): Double {
        if (sensorHfov <= 0.0 || sensorVfov <= 0.0) return Math.toRadians(70.0)
        return if (rotation == 90 || rotation == 270) sensorVfov else sensorHfov
    }

    companion object {
        private const val TAG = "Jiadroid"
        private const val HOLD_MS = 400L
    }
}

package dev.jiadroid.follow

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
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
    private var loggedFrame = false
    private var gazeY = 0f
    private val pose = Pose(0f, 0f, (Math.PI / 2.0).toFloat())
    private var gait = 0f

    private var subject = SUBJECT_MARKER
    private var situationText = "Marker is lost"
    private lateinit var voice: VoiceSession
    private val talk = ArrayDeque<String>()
    private var hearing: String? = null

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    private val bodies: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
    )

    private val requestSenses = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera() else binding.link.text = getString(R.string.camera_denied)
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            if (ticking) voice.start()
        } else {
            binding.voiceLine.text = getString(R.string.mic_denied)
        }
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
        showDebug(false)
        binding.eyes.setOnClickListener { showDebug(true) }
        binding.openDebug.setOnClickListener { showDebug(true) }
        binding.closeDebug.setOnClickListener { showDebug(false) }
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
        voice = VoiceSession(
            this,
            seeing = { situationText },
            onLine = { binding.voiceLine.text = it },
            onTurn = ::logTurn,
            onHearing = ::logHearing,
        )
        binding.saySend.setOnClickListener { sayTyped() }
        binding.say.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sayTyped()
                true
            } else {
                false
            }
        }
        val prefs = getPreferences(MODE_PRIVATE)
        binding.brainUrl.setText(prefs.getString(PREF_BRAIN_URL, ""))
        binding.brainModel.setText(prefs.getString(PREF_BRAIN_MODEL, ""))
        binding.brainKey.setText(prefs.getString(PREF_BRAIN_KEY, ""))
        binding.brainUse.setOnClickListener { useBrain(save = true) }
        useBrain(save = false)
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startCamera()
        } else {
            requestSenses.launch(needed.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        ticking = true
        lastTickNs = 0L
        handler.post(tick)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start()
        }
    }

    override fun onPause() {
        ticking = false
        handler.removeCallbacks(tick)
        if (::voice.isInitialized) voice.stop()
        send(decide(Scene(false, 0f, 0f)), force = true)
        super.onPause()
    }

    override fun onDestroy() {
        val robot = link.getAndSet(null)
        if (robot != null) release(robot)
        cameraProvider?.unbindAll()
        if (::voice.isInitialized) voice.destroy()
        scanner.close()
        bodies.close()
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
            binding.overlay.setMarker(null, 0, 0, "")
        }
        val decision = decide(scene, subject)
        if (decision.command == "STOP") gait = 0f else gait += dt * 7f
        stepPose(pose, decision.forward, decision.lateral, decision.yaw, dt)
        binding.duck.render(pose, scene, gait, hfovRad.toFloat())
        binding.eyes.render(decision, if (scene.visible) gazeY else 0f)
        show(decision, scene)
        send(decision, force = false)
    }

    private fun logTurn(who: String, text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { logTurn(who, text) }
            return
        }
        hearing = null
        talk.addLast("$who: $text")
        while (talk.size > TALK_LINES) talk.removeFirst()
        showTalk()
    }

    private fun logHearing(text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { logHearing(text) }
            return
        }
        hearing = "Hearing: $text"
        binding.voiceLine.text = hearing
        showTalk()
    }

    private fun showTalk() {
        val lines = ArrayList<String>(talk.size + 1)
        lines.addAll(talk)
        hearing?.let { lines.add(it) }
        val shown = if (lines.isEmpty()) getString(R.string.talk_empty) else lines.joinToString("\n")
        binding.talkLog.text = shown
        if (lines.isNotEmpty()) binding.voiceLine.text = lines.last()
        binding.talkScroll.post { binding.talkScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun sayTyped() {
        val text = binding.say.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.say.text = null
        hideKeyboard()
        voice.answer(text)
    }

    private fun useBrain(save: Boolean) {
        val url = binding.brainUrl.text?.toString()?.trim().orEmpty()
        val model = binding.brainModel.text?.toString()?.trim().orEmpty()
        val key = binding.brainKey.text?.toString()?.trim().orEmpty()
        if (save) {
            getPreferences(MODE_PRIVATE).edit()
                .putString(PREF_BRAIN_URL, url)
                .putString(PREF_BRAIN_MODEL, model)
                .putString(PREF_BRAIN_KEY, key)
                .apply()
            hideKeyboard()
        }
        voice.brain = if (url.isEmpty() || model.isEmpty()) null else Brain(url, model, key)
        binding.brainStatus.text = if (voice.brain == null) {
            getString(R.string.brain_local)
        } else {
            getString(R.string.brain_on, model, url)
        }
    }

    private fun showDebug(show: Boolean) {
        // Keep Debug mode laid out behind the eyes. CameraX needs PreviewView's
        // surface even when the user only sees the normal eyes screen.
        binding.debugMode.visibility = android.view.View.VISIBLE
        binding.normalMode.visibility = if (show) android.view.View.GONE else android.view.View.VISIBLE
        if (!show) binding.normalMode.bringToFront()
        currentFocus?.clearFocus()
        hideKeyboard()
        // Showing the camera preview can reset the emulator microphone. Open it again.
        if (::voice.isInitialized) handler.postDelayed({ voice.reopen() }, 400)
    }

    private fun show(decision: FollowDecision, scene: Scene) {
        val moving = decision.command != "STOP"
        situationText = decision.situation
        binding.situation.text = decision.situation
        binding.command.text = decision.command
        binding.command.setTextColor(ContextCompat.getColor(this, if (moving) R.color.go else R.color.stop))
        val range = if (scene.visible) String.format(Locale.US, "%.2f m", scene.distance) else "nothing seen"
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

    private fun personHeightM(): Float {
        val mm = binding.personMm.text?.toString()?.toFloatOrNull() ?: return PERSON_HEIGHT_MM / 1000f
        if (mm < 100f || mm > 2500f) return PERSON_HEIGHT_MM / 1000f
        return mm / 1000f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindCamera(provider)
            } catch (error: Exception) {
                binding.situation.text = error.message ?: getString(R.string.no_front_camera)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            binding.situation.text = getString(R.string.no_front_camera)
            return
        }
        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
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
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            readHfov(camera)
        } catch (error: Exception) {
            binding.situation.text = error.message ?: getString(R.string.no_front_camera)
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
        if (!loggedFrame) {
            loggedFrame = true
            Log.i(TAG, "frame ${image.width}x${image.height} rot=$rotation crop=${image.cropRect}")
        }
        try {
            val input = InputImage.fromMediaImage(media, rotation)
            scanner.process(input)
                .continueWithTask { codes ->
                    val hit = if (codes.isSuccessful) codes.result.firstOrNull { it.rawValue == MARKER_PAYLOAD } else null
                    val corners = hit?.let(::cornersOf)
                    if (corners != null) {
                        handler.post {
                            if (!isDestroyed) onMarker(corners, uprightWidth, uprightHeight, rotation)
                        }
                        return@continueWithTask Tasks.forResult<Unit?>(null)
                    }
                    bodies.process(input).continueWith { found ->
                        if (found.isSuccessful) {
                            val body = found.result
                            handler.post {
                                if (!isDestroyed) onPerson(body, uprightWidth, uprightHeight, rotation)
                            }
                        }
                        null
                    }
                }
                .addOnCompleteListener { image.close() }
        } catch (error: Exception) {
            image.close()
            Log.i(TAG, "marker scan failed", error)
        }
    }

    private fun onMarker(corners: List<PointF>, imageWidth: Int, imageHeight: Int, rotation: Int) {
        val placed = uprightCorners(corners, rotation, imageWidth, imageHeight)
        gazeY = (((placed.map { it.y }.average().toFloat() / imageHeight) - 0.5f) * 2f)
            .coerceIn(-1f, 1f)
        val centerX = placed.map { it.x }.average().toFloat()
        val widthPx = minOf(
            hypot(placed[1].x - placed[0].x, placed[1].y - placed[0].y),
            hypot(placed[2].x - placed[3].x, placed[2].y - placed[3].y),
            hypot(placed[2].x - placed[1].x, placed[2].y - placed[1].y),
            hypot(placed[0].x - placed[3].x, placed[0].y - placed[3].y),
        )
        hfovRad = hfovFor(rotation, imageWidth, imageHeight)
        val measured = measure(centerX, widthPx, imageWidth, hfovRad, markerWidthM())
        Log.i(
            TAG,
            "marker ${"%.0f".format(Locale.US, widthPx)}px in ${imageWidth}x$imageHeight " +
                "rot=$rotation fov=${"%.0f".format(Locale.US, Math.toDegrees(hfovRad))} " +
                "mm=${"%.0f".format(Locale.US, markerWidthM() * 1000f)} dist=${"%.2f".format(Locale.US, measured.distance)}",
        )
        accept(measured, SUBJECT_MARKER, placed, imageWidth, imageHeight)
    }

    private fun onPerson(body: com.google.mlkit.vision.pose.Pose, imageWidth: Int, imageHeight: Int, rotation: Int) {
        if (subject == SUBJECT_MARKER && lastScene.visible &&
            SystemClock.elapsedRealtime() - lastMarkerAt < HOLD_MS
        ) {
            return
        }
        val leftShoulder = seen(body, PoseLandmark.LEFT_SHOULDER) ?: return
        val rightShoulder = seen(body, PoseLandmark.RIGHT_SHOULDER) ?: return
        val nose = seen(body, PoseLandmark.NOSE)
        val leftHip = seen(body, PoseLandmark.LEFT_HIP)
        val rightHip = seen(body, PoseLandmark.RIGHT_HIP)
        val hips = if (leftHip != null && rightHip != null) leftHip to rightHip else null
        if (nose != null) {
            gazeY = (((nose.y / imageHeight) - 0.5f) * 2f).coerceIn(-1f, 1f)
        }
        hfovRad = hfovFor(rotation, imageWidth, imageHeight)
        val measured = measurePerson(leftShoulder to rightShoulder, hips, imageWidth, hfovRad, personHeightM())
        Log.i(
            TAG,
            "person shoulders ${"%.0f".format(Locale.US, leftShoulder.distanceTo(rightShoulder))}px " +
                "hips=${hips != null} in ${imageWidth}x$imageHeight " +
                "mm=${"%.0f".format(Locale.US, personHeightM() * 1000f)} dist=${"%.2f".format(Locale.US, measured.distance)}",
        )
        val shoulderWidth = leftShoulder.distanceTo(rightShoulder)
        val lowLeft = leftHip ?: Point(leftShoulder.x, leftShoulder.y + shoulderWidth)
        val lowRight = rightHip ?: Point(rightShoulder.x, rightShoulder.y + shoulderWidth)
        val box = listOf(rightShoulder, leftShoulder, lowLeft, lowRight).map { PointF(it.x, it.y) }
        accept(measured, SUBJECT_PERSON, box, imageWidth, imageHeight)
    }

    private fun seen(body: com.google.mlkit.vision.pose.Pose, type: Int): Point? {
        val landmark = body.getPoseLandmark(type) ?: return null
        if (landmark.inFrameLikelihood < BODY_LIKELIHOOD) return null
        return Point(landmark.position.x, landmark.position.y)
    }

    private fun accept(measured: Scene, seenSubject: String, corners: List<PointF>, imageWidth: Int, imageHeight: Int) {
        if (!measured.visible) return
        if (seenSubject != subject) {
            subject = seenSubject
            lastScene = Scene(false, 0f, 0f)
        }
        lastScene = smoothScene(lastScene, measured)
        lastMarkerAt = SystemClock.elapsedRealtime()
        markerCorners = corners
        markerImageWidth = imageWidth
        markerImageHeight = imageHeight
        val label = if (seenSubject == SUBJECT_PERSON) "person" else "mini person"
        binding.overlay.setMarker(corners, imageWidth, imageHeight, label)
    }

    /** Corner points are upright. If they still sit in the raw buffer, rotate them. */
    private fun uprightCorners(
        corners: List<PointF>,
        rotation: Int,
        uprightWidth: Int,
        uprightHeight: Int,
    ): List<PointF> {
        val maxX = corners.maxOf { it.x }
        val maxY = corners.maxOf { it.y }
        if (maxX <= uprightWidth + 1f && maxY <= uprightHeight + 1f) return corners
        val bufferWidth = if (rotation == 90 || rotation == 270) uprightHeight else uprightWidth
        val bufferHeight = if (rotation == 90 || rotation == 270) uprightWidth else uprightHeight
        return when (rotation) {
            90 -> corners.map { PointF(it.y, bufferWidth - it.x) }
            270 -> corners.map { PointF(bufferHeight - it.y, it.x) }
            180 -> corners.map { PointF(bufferWidth - it.x, bufferHeight - it.y) }
            else -> corners
        }
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
            // The emulator's webcam reports a 1 mm lens. That makes the picture look
            // about 100 degrees wide, so a marker across the room is scored as too close.
            if (focalMm < 2.0) {
                Log.i(TAG, "ignoring placeholder focal length ${focalMm}mm")
                return
            }
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

    private fun hfovFor(rotation: Int, uprightWidth: Int, uprightHeight: Int): Double {
        return uprightFieldOfView(rotation, uprightWidth, uprightHeight, sensorHfov, sensorVfov)
    }

    companion object {
        private const val TAG = "Jiadroid"
        private const val HOLD_MS = 400L
        private const val BODY_LIKELIHOOD = 0.7f
        private const val SUBJECT_MARKER = "Marker"
        private const val SUBJECT_PERSON = "Person"
        private const val TALK_LINES = 40
        private const val PREF_BRAIN_URL = "brain_url"
        private const val PREF_BRAIN_MODEL = "brain_model"
        private const val PREF_BRAIN_KEY = "brain_key"
    }
}

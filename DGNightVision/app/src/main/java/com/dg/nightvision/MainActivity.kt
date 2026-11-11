package com.dg.nightvision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val processor = NightProcessor()
    private val server = MjpegServer(8080)
    private lateinit var executor: ExecutorService
    private lateinit var frameView: FrameView
    private var camera: Camera? = null
    private var yBuf = ByteArray(0)
    private var lastJpeg = 0L
    private var longExp = false
    private lateinit var topBar: LinearLayout
    private lateinit var bar: LinearLayout

    private val askCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera()
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        executor = Executors.newSingleThreadExecutor()

        frameView = FrameView(this)
        val root = FrameLayout(this)
        root.addView(frameView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
        }
        val info = TextView(this).apply {
            setTextColor(0xFF99FF99.toInt())
            setPadding(24, 12, 16, 12)
        }
        topBar.addView(info)
        val vrBtn = Button(this).apply {
            text = "VR"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(24, 0, 24, 0)
            setOnClickListener { setVr(true) }
        }
        topBar.addView(vrBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(topBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // VR modunda: tek dokunuş = mod değiştir, çift dokunuş = VR'dan çık
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!frameView.vrMode) return false
                processor.mode = when (processor.mode) {
                    NightProcessor.Mode.NIGHT -> NightProcessor.Mode.THERMAL
                    NightProcessor.Mode.THERMAL -> NightProcessor.Mode.RAW
                    NightProcessor.Mode.RAW -> NightProcessor.Mode.NIGHT
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!frameView.vrMode) return false
                setVr(false)
                return true
            }
        })
        frameView.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
        }
        fun btn(t: String, f: () -> Unit): Button =
            Button(this).apply {
                text = t
                isAllCaps = false
                setOnClickListener { f() }
            }.also { bar.addView(it) }

        btn("Night") { processor.mode = NightProcessor.Mode.NIGHT }
        btn("Thermal effect") { processor.mode = NightProcessor.Mode.THERMAL }
        btn("Raw") { processor.mode = NightProcessor.Mode.RAW }
        val expBtn = btn("Long exposure: off") {}
        expBtn.setOnClickListener {
            longExp = !longExp
            setLongExposure(longExp)
            expBtn.text = if (longExp) "Long exposure: on" else "Long exposure: off"
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    processor.gain = 0.5f + p / 100f * 2.5f
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bar.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        server.start()
        info.text = "DGNightVision"
        // Tap the title to show the Quest address
        info.setOnClickListener {
            Toast.makeText(this, "Quest browser: http://${localIp()}:8080", Toast.LENGTH_LONG).show()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    /** VR gözlük modu: görüntü iki göz için yan yana, butonlar ve sistem çubukları gizli. */
    private fun setVr(on: Boolean) {
        frameView.vrMode = on
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        bar.visibility = if (on) View.GONE else View.VISIBLE
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "VR: tap = change mode, double tap = exit", Toast.LENGTH_LONG).show()
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        frameView.invalidate()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { analyze(it) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            if (longExp) setLongExposure(true)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            if (yBuf.size != w * h) yBuf = ByteArray(w * h)
            if (rs == w) {
                buf.position(0); buf.get(yBuf, 0, w * h)
            } else {
                for (r in 0 until h) { buf.position(r * rs); buf.get(yBuf, r * w, w) }
            }

            val px = processor.process(yBuf, w, h)
            val bmp = frameView.ensureBitmap(w, h)
            synchronized(bmp) { bmp.setPixels(px, 0, w, 0, 0, w, h) }
            val rot = img.imageInfo.rotationDegrees
            frameView.rotationDeg = rot
            frameView.postInvalidate()

            val now = SystemClock.uptimeMillis()
            if (server.hasClients && now - lastJpeg > 66) {
                lastJpeg = now
                server.update(toJpeg(bmp, rot))
            }
        } finally {
            img.close()
        }
    }

    private fun toJpeg(b: Bitmap, rot: Int): ByteArray {
        val src = synchronized(b) {
            if (rot == 0) b.copy(Bitmap.Config.ARGB_8888, false)
            else Bitmap.createBitmap(b, 0, 0, b.width, b.height,
                Matrix().apply { postRotate(rot.toFloat()) }, false)
        }
        val bos = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        src.recycle()
        return bos.toByteArray()
    }

    /** Otomatik pozlamayı kapatıp 1/10 sn pozlama + en yüksek ISO kullanır. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun setLongExposure(on: Boolean) {
        val cam = camera ?: return
        val ctrl = Camera2CameraControl.from(cam.cameraControl)
        if (!on) {
            ctrl.clearCaptureRequestOptions()
            return
        }
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso: Int = isoRange?.upper ?: 3200
        val exp: Long = minOf(100_000_000L, expRange?.upper ?: 100_000_000L)
        ctrl.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, exp)
                .build()
        )
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress ?: "no WiFi"
    } catch (_: Exception) { "?" }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
        executor.shutdown()
    }
}

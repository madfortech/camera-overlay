package com.telescript.cameraoverlay

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraOverlayActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCRIPT = "script"
        const val EXTRA_ID = "id"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MAX_DURATION = "maxDuration"
        const val EXTRA_COUNTDOWN = "countdown"
        const val EXTRA_ORIENTATION = "orientation"
        private const val TAG = "CameraOverlayActivity"
        private const val REQ_PERMS = 1001
    }

    private var previewView: PreviewView? = null
    private var overlayText: TextView? = null
    private var overlayScroll: ScrollView? = null
    private var statusText: TextView? = null
    private var stopButton: TextView? = null
    private var flipButton: TextView? = null
    private var pauseButton: TextView? = null
    private var speedButton02: TextView? = null
    private var speedButton05: TextView? = null
    private var speedButton1: TextView? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraExecutor: ExecutorService? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private val handler = Handler(Looper.getMainLooper())
    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var scrollRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var elapsedRunnable: Runnable? = null
    private var recordingStartMs = 0L
    private var finished = false
    private var cancelled = false
    private var cancelDispatched = false
    private var switchingCamera = false
    private var autoScrolling = true
    private var scrollSpeed = 1.0f
    private var scrollAccumulator = 0f
    private var recordingId: String? = null
    private var eventClass: String? = null
    private var maxDurationSec: Int = 0
    private var countdownSec: Int = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val orientation = intent.getStringExtra(EXTRA_ORIENTATION) ?: "portrait"
        requestedOrientation = if (orientation == "landscape") {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        recordingId = intent.getStringExtra(EXTRA_ID)
        eventClass = intent.getStringExtra(EXTRA_EVENT)
        maxDurationSec = intent.getIntExtra(EXTRA_MAX_DURATION, 0)
        countdownSec = intent.getIntExtra(EXTRA_COUNTDOWN, 3).coerceAtLeast(0)
        val script = intent.getStringExtra(EXTRA_SCRIPT) ?: ""
        chunks = buildChunks(script)

        cameraExecutor = Executors.newSingleThreadExecutor()
        setContentView(buildUi())

        if (hasPermissions()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMS)
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && hasPermissions()) {
            startCamera()
        } else if (requestCode == REQ_PERMS) {
            cancelled = true
            dispatchCancelled()
            finishSafely()
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        val overlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#66000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(260),
                Gravity.TOP
            )
        }

        overlayScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
        }

        overlayText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(8), dp(24), dp(8), dp(140))
            text = intent.getStringExtra(EXTRA_SCRIPT)?.trim().orEmpty()
        }
        overlayScroll?.addView(overlayText)
        overlayContainer.addView(overlayScroll)
        root.addView(overlayContainer)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(20))
            setBackgroundColor(Color.parseColor("#66000000"))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            lp.bottomMargin = dp(56)
            layoutParams = lp
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Recording..."
        }
        bottom.addView(statusText)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        controls.addView(controlButton("UP", "#CC334155") { scrollByAmount(-dp(220)) })
        controls.addView(controlButton("DOWN", "#CC334155") { scrollByAmount(dp(220)) })
        pauseButton = controlButton("PAUSE", "#CC475569") { toggleAutoScroll() }
        controls.addView(pauseButton)
        stopButton = controlButton("STOP", "#CC0f766e") { stopRecording() }
        controls.addView(stopButton)

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        speedButton02 = controlButton("0.2x", "#CC334155") { setScrollSpeed(0.2f) }
        speedButton05 = controlButton("0.5x", "#CC334155") { setScrollSpeed(0.5f) }
        speedButton1 = controlButton("1x", "#CC0f766e") { setScrollSpeed(1.0f) }
        speedRow.addView(speedButton02)
        speedRow.addView(speedButton05)
        speedRow.addView(speedButton1)
        flipButton = controlButton("FLIP", "#CC1d4ed8") { toggleCamera() }
        speedRow.addView(flipButton)

        bottom.addView(controls)
        bottom.addView(speedRow)
        root.addView(bottom)

        return root
    }

    private fun controlButton(label: String, color: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundColor(Color.parseColor(color))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(4)
            lp.marginEnd = dp(4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun setScrollSpeed(speed: Float) {
        scrollSpeed = speed
        scrollAccumulator = 0f
        val active = "#CC0f766e"
        val inactive = "#CC334155"
        speedButton02?.setBackgroundColor(Color.parseColor(if (speed == 0.2f) active else inactive))
        speedButton05?.setBackgroundColor(Color.parseColor(if (speed == 0.5f) active else inactive))
        speedButton1?.setBackgroundColor(Color.parseColor(if (speed == 1.0f) active else inactive))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildChunks(script: String): List<String> {
        val words = script.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val end = minOf(i + 6, words.size)
            out.add(words.subList(i, end).joinToString(" "))
            i = end
        }
        return out
    }

    private fun startCamera() {
        val providerFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(Runnable {
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindUseCases()
                startCountdownThenRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                if (switchingCamera) {
                    switchingCamera = false
                    statusText?.text = "Camera switch failed"
                } else {
                    cancelled = true
                    dispatchCancelled()
                    finishSafely()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: throw IllegalStateException("Camera provider not ready")
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView?.surfaceProvider
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        val vc = VideoCapture.withOutput(recorder)
        videoCapture = vc

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview, vc)
    }

    private fun toggleCamera() {
        if (finished || switchingCamera) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        switchingCamera = true
        statusText?.text = "Switching camera..."
        stopScroll()
        val activeRecording = recording
        if (activeRecording != null) {
            try {
                activeRecording.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Stop for switch failed", e)
                switchingCamera = false
                statusText?.text = "Recording..."
                startScrolling()
            }
        } else {
            try {
                bindUseCases()
                startRecording()
                startScrolling()
            } catch (e: Exception) {
                Log.e(TAG, "Switch bind failed", e)
            } finally {
                switchingCamera = false
            }
        }
    }

    private fun startCountdownThenRecording() {
        stopButton?.isEnabled = false
        if (countdownSec <= 0) {
            statusText?.text = "Recording..."
            startRecording()
            startScrolling()
            return
        }

        var remaining = countdownSec
        statusText?.text = "Starting in $remaining"
        val runnable = object : Runnable {
            override fun run() {
                if (finished || cancelled) return
                remaining--
                if (remaining > 0) {
                    statusText?.text = "Starting in $remaining"
                    countdownRunnable = this
                    handler.postDelayed(this, 1000L)
                } else {
                    countdownRunnable = null
                    statusText?.text = "Recording..."
                    startRecording()
                    startScrolling()
                }
            }
        }
        countdownRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun startElapsedTimer() {
        recordingStartMs = System.currentTimeMillis()
        elapsedRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (finished || cancelled) return
                val totalSeconds = (System.currentTimeMillis() - recordingStartMs) / 1000
                val elapsed = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
                statusText?.text = "Recording $elapsed"
                handler.postDelayed(this, 1000L)
            }
        }
        elapsedRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopElapsedTimer() {
        elapsedRunnable?.let { handler.removeCallbacks(it) }
        elapsedRunnable = null
    }

    private fun startRecording() {
        val vc = videoCapture ?: return
        val videoDir = File(noBackupFilesDir, "Camera").apply { mkdirs() }
        val outFile = File(videoDir, "overlay_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(outFile).build()

        var pending = vc.output
            .prepareRecording(this, outputOptions)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        try { recording?.close() } catch (_: Exception) {}
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    stopButton?.isEnabled = true
                    startElapsedTimer()
                }
                is VideoRecordEvent.Finalize -> {
                    handleFinalize(event, outFile)
                }
            }
        }

        if (maxDurationSec > 0) {
            handler.removeCallbacksAndMessages("maxDuration")
            handler.postDelayed({ stopRecording() }, "maxDuration", maxDurationSec * 1000L)
        }
    }

    private fun startScrolling() {
        val sv = overlayScroll ?: return
        stopScroll()
        scrollAccumulator = 0f
        sv.post {
            sv.scrollTo(0, 0)
            val runnable = object : Runnable {
                override fun run() {
                    if (finished || switchingCamera) return
                    if (autoScrolling) {
                        scrollAccumulator += 2f * scrollSpeed
                        val pixels = scrollAccumulator.toInt()
                        if (pixels > 0) {
                            sv.scrollBy(0, pixels)
                            scrollAccumulator -= pixels
                            val child = sv.getChildAt(0)
                            if (child != null && child.height > sv.height && sv.scrollY + sv.height >= child.height - dp(8)) {
                                sv.scrollTo(0, 0)
                            }
                        }
                    }
                    handler.postDelayed(this, 40)
                }
            }
            scrollRunnable = runnable
            handler.postDelayed(runnable, 600)
        }
    }

    private fun scrollByAmount(delta: Int) {
        val sv = overlayScroll ?: return
        sv.smoothScrollBy(0, delta)
    }

    private fun toggleAutoScroll() {
        autoScrolling = !autoScrolling
        pauseButton?.text = if (autoScrolling) "PAUSE" else "PLAY"
    }

    private fun stopRecording() {
        if (finished || switchingCamera) return
        cancelCountdown()
        stopElapsedTimer()
        statusText?.text = "Saving..."
        stopButton?.isEnabled = false
        flipButton?.isEnabled = false
        pauseButton?.isEnabled = false
        handler.removeCallbacksAndMessages("maxDuration")
        try {
            recording?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
        }
    }

    private fun handleFinalize(event: VideoRecordEvent.Finalize, outFile: File) {
        stopElapsedTimer()
        val wasSwitching = switchingCamera
        if (wasSwitching) {
            stopScroll()
            try { recording?.close() } catch (_: Exception) {}
            recording = null
            outFile.delete()
            switchingCamera = false
            if (finished || cancelled) {
                finishSafely()
                return
            }
            try {
                bindUseCases()
                startRecording()
                startScrolling()
                statusText?.text = "Recording..."
            } catch (e: Exception) {
                Log.e(TAG, "Restart after switch failed", e)
                cancelled = true
                dispatchCancelled()
                finishSafely()
            }
            return
        }

        if (finished) return
        stopScroll()
        if (cancelled || event.hasError()) {
            if (event.hasError()) {
                Log.e(TAG, "Recording error: ${event.error}")
            }
            outFile.delete()
            dispatchCancelled()
        } else {
            val path = outFile.absolutePath
            val payload = JSONObject().apply {
                put("path", path)
                put("mimeType", "video/mp4")
                recordingId?.let { put("id", it) }
            }
            val evt = eventClass ?: "Native\\Mobile\\Events\\Camera\\VideoRecorded"
            try {
                NativeActionCoordinator.dispatchEvent(this, evt, payload.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Dispatch failed", e)
            }
        }
        finishSafely()
    }

    private fun dispatchCancelled() {
        if (cancelDispatched) return
        cancelDispatched = true
        val payload = JSONObject().apply {
            put("cancelled", true)
            recordingId?.let { put("id", it) }
        }
        try {
            NativeActionCoordinator.dispatchEvent(this, "Native\\Mobile\\Events\\Camera\\VideoCancelled", payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Cancel dispatch failed", e)
        }
    }

    private fun cancelAndStop() {
        if (finished || cancelled) return
        cancelled = true
        switchingCamera = false
        cancelCountdown()
        stopElapsedTimer()
        stopScroll()
        statusText?.text = "Cancelled"
        stopButton?.isEnabled = false
        flipButton?.isEnabled = false
        pauseButton?.isEnabled = false
        handler.removeCallbacksAndMessages("maxDuration")
        dispatchCancelled()
        val activeRecording = recording
        if (activeRecording != null) {
            try {
                activeRecording.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Cancel stop failed", e)
                finishSafely()
                return
            }
            handler.postDelayed({ finishSafely() }, 1500)
        } else {
            finishSafely()
        }
    }

    private fun stopScroll() {
        scrollRunnable?.let { handler.removeCallbacks(it) }
        scrollRunnable = null
    }

    private fun finishSafely() {
        if (finished) return
        finished = true
        stopScroll()
        handler.removeCallbacksAndMessages(null)
        try { recording?.close() } catch (_: Exception) {}
        recording = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        runOnUiThread {
            if (!isFinishing) finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancelAndStop()
    }

    override fun onDestroy() {
        stopScroll()
        handler.removeCallbacksAndMessages(null)
        try { cameraExecutor?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
package com.telescript.cameraoverlay

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CameraOverlayFunctions {

    class RecordVideo(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val script = parameters["script"] as? String ?: ""
            val id = parameters["id"] as? String
            val event = parameters["event"] as? String
            val maxDuration = (parameters["maxDuration"] as? Number)?.toInt() ?: 0
            val countdown = (parameters["countdown"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 3

            Log.d("CameraOverlay.Record", "Launching overlay recorder id=$id duration=$maxDuration countdown=$countdown")

            Handler(Looper.getMainLooper()).post {
                try {
                    val intent = Intent(activity, CameraOverlayActivity::class.java).apply {
                        putExtra(CameraOverlayActivity.EXTRA_SCRIPT, script)
                        putExtra(CameraOverlayActivity.EXTRA_ID, id)
                        putExtra(CameraOverlayActivity.EXTRA_EVENT, event)
                        putExtra(CameraOverlayActivity.EXTRA_MAX_DURATION, maxDuration)
                        putExtra(CameraOverlayActivity.EXTRA_COUNTDOWN, countdown)
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("CameraOverlay.Record", "Failed to launch activity", e)
                }
            }

            return mapOf("status" to "success")
        }
    }

    /**
     * Copies an app-private video to shared storage so it appears in the phone gallery.
     */
    class SaveToGallery(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val path = parameters["path"] as? String
            if (path.isNullOrBlank()) {
                return mapOf("status" to "error", "message" to "Missing video path")
            }

            val source = File(path)
            if (! source.isFile) {
                return mapOf("status" to "error", "message" to "Video file not found")
            }

            val displayName = "TeleScript_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4"

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(source, displayName)
                } else {
                    saveLegacy(source, displayName)
                }
                mapOf("status" to "success")
            } catch (e: Exception) {
                Log.e("CameraOverlay.Save", "Failed to save video to gallery", e)
                mapOf("status" to "error", "message" to (e.message ?: "Unknown error"))
            }
        }

        private fun saveViaMediaStore(source: File, displayName: String) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TeleScript")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create gallery entry")

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not open gallery file")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }

        private fun saveLegacy(source: File, displayName: String) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Storage permission not granted")
            }

            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TeleScript")
            if (! directory.exists() && ! directory.mkdirs()) {
                throw IOException("Could not create gallery folder")
            }
            val destination = File(directory, displayName)
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            MediaScannerConnection.scanFile(
                activity.applicationContext,
                arrayOf(destination.absolutePath),
                arrayOf("video/mp4"),
                null
            )
        }
    }
}

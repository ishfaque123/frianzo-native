package com.frianzo.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * v3.1 — the four owner-confirmed permissions: Notification, Gallery,
 * Camera, Microphone. Location is deliberately NOT here: no feature needs
 * it. Each permission is asked AT FEATURE USE, never all at launch:
 * - Notification: once after login (MainActivity AppShell).
 * - Gallery: when the user taps a photo/video pick button (all pickers
 *   below go through rememberPermissionGate).
 * - Camera + Microphone: when the user taps "Record a video" in reel
 *   upload (TakeVideo needs both: camera for picture, mic for sound).
 * The system photo picker works without a permission on its own, but the
 * owner wants the Gallery permission asked at pick time, so the gate
 * requests READ_MEDIA_IMAGES/VIDEO (Android 13+) or READ_EXTERNAL_STORAGE
 * (older) before opening the picker.
 */

/** Gallery permission set for this device's Android version. */
fun galleryPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** Camera + microphone, needed together to record a video with sound. */
fun cameraMicPermissions(): Array<String> =
    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/**
 * Returns a function that runs [onGranted] immediately if all [permissions]
 * are already granted, otherwise asks for them first and runs [onGranted]
 * only if the user grants all of them.
 */
@Composable
fun rememberPermissionGate(permissions: Array<String>, onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) onGranted()
    }
    return remember(permissions.contentHashCode()) {
        {
            if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                onGranted()
            } else {
                launcher.launch(permissions)
            }
        }
    }
}

/** Creates a MediaStore Uri for TakeVideo to record into (no FileProvider needed). */
fun createVideoUri(context: Context): Uri? {
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "frianzo-reel-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Frianzo")
            }
        }
        context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    } catch (_: Exception) {
        null
    }
}

package com.frianzo.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Location (v3.4) is a fifth, composer-only permission: ACCESS_FINE_LOCATION
 * + ACCESS_COARSE_LOCATION, asked only when the user taps "Add location".
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

// v3.4: Location — used ONLY by the composer "Add location" button
// (device GPS -> GET /api/geo/reverse -> location name). Asked at tap
// time, never at launch. If denied, the composer falls back to a plain
// text field for the location instead of faking one.
fun locationPermissions(): Array<String> =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * Returns a function that runs [onGranted] immediately if all [permissions]
 * are already granted, otherwise asks for them first and runs [onGranted]
 * only if the user grants all of them.
 *
 * C-B19: [onGranted] is read through rememberUpdatedState, so a changed
 * callback (e.g. a different pending media Uri) is never stale — the
 * cached gate lambda always calls the latest one.
 * C-B19 + C-D3: on denial the pick button no longer goes silently dead:
 * [onDenied] runs when supplied; otherwise a Toast explains that the
 * permission can be allowed in the app's Settings.
 */
@Composable
fun rememberPermissionGate(permissions: Array<String>, onGranted: () -> Unit, onDenied: (() -> Unit)? = null): () -> Unit {
    val context = LocalContext.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    val currentOnDenied by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            currentOnGranted()
        } else {
            currentOnDenied?.invoke() ?: Toast.makeText(
                context,
                "Permission denied — allow it in the app Settings to use this.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    return remember(permissions.contentHashCode()) {
        {
            if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                currentOnGranted()
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

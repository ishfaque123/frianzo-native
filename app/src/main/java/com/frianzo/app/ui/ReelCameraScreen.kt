// ---------- IN-APP CAMERA RECORDER (v5.2, web-like rebuild): TikTok-style
// recorder rebuilt to match the live web camera page
// (vynzo-frontend src/app/reels/camera/page.tsx) exactly — same tools,
// same places, same sizes. 15s / 60s / 3m pill (default 60s), 12 effects
// as circular face thumbnails, "All effects" sheet (Trending / Games),
// right toolbar (Timer / Speed / Photo mode) with popups, record row
// (Gallery / Record / Flip), recording pill + progress at the TOP.
// CameraX (no system camera intent). Honest limits: the 12 effects are
// chosen here and applied by the SERVER at upload (the server filter
// enum); there is no real-time GPU processing of the preview — the tint
// overlay is only a preview hint of the chosen effect, and the face
// thumbnails show the effect via a ColorMatrix (the web's CSS chain).
// Speed is likewise an upload value, not a live preview speed. No
// backdrop blur (Compose has no backdrop-blur API for the background
// behind a composable); the pills use the web's black/40 and black/70
// backgrounds, which read the same. The recording red dot does not
// pulse (the web's animate-pulse is a CSS animation; a static dot is
// used instead). v5.20 (M4): a PHOTO capture bakes the chosen effect
// into the JPEG client-side (bakeEffectIntoPhoto) — the video path
// still relies on the server filter at upload.
package com.frianzo.app.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.foundation.Image as FoundationImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.frianzo.app.R
import java.io.File
import kotlinx.coroutines.delay

private fun formatRecTime(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

// 4x5 colour matrices (Compose ColorMatrix = 20 floats, translation in
// 0..1). Same technique as the editor staging's filterColorMatrix, but
// these are the web CAMERA page's CSS filter chains (not the upload
// page's), composed into one matrix so the face thumbnails show the REAL
// effect look.
private fun camMatIdentity(): FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

// Returns a*b (b is applied first, then a) for 4x5 matrices.
private fun camMatMul(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(20)
    for (r in 0 until 4) {
        for (c in 0 until 5) {
            var v = 0f
            for (k in 0 until 4) v += a[r * 5 + k] * b[k * 5 + c]
            if (c == 4) v += a[r * 5 + 4]
            out[r * 5 + c] = v
        }
    }
    return out
}

private fun camMatSaturation(s: Float): FloatArray {
    val rw = 0.213f; val gw = 0.715f; val bw = 0.072f
    return floatArrayOf(
        rw + (1f - rw) * s, gw - gw * s, bw - bw * s, 0f, 0f,
        rw - rw * s, gw + (1f - gw) * s, bw - bw * s, 0f, 0f,
        rw - rw * s, gw - gw * s, bw + (1f - bw) * s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun camMatContrast(c: Float): FloatArray {
    val t = 0.5f * (1f - c)
    return floatArrayOf(
        c, 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c, 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun camMatBrightness(b: Float): FloatArray = floatArrayOf(
    b, 0f, 0f, 0f, 0f,
    0f, b, 0f, 0f, 0f,
    0f, 0f, b, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

private fun camMatSepia(a: Float): FloatArray {
    val sep = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    val id = camMatIdentity()
    return FloatArray(20) { i -> id[i] * (1f - a) + sep[i] * a }
}

private fun camMatHueRotate(deg: Float): FloatArray {
    val rad = Math.toRadians(deg.toDouble())
    val cos = Math.cos(rad).toFloat(); val sin = Math.sin(rad).toFloat()
    val rw = 0.213f; val gw = 0.715f; val bw = 0.072f
    return floatArrayOf(
        rw + cos * (1f - rw) + sin * (-rw), gw + cos * (-gw) + sin * (-gw), bw + cos * (-bw) + sin * (1f - bw), 0f, 0f,
        rw + cos * (-rw) + sin * 0.143f, gw + cos * (1f - gw) + sin * 0.140f, bw + cos * (-bw) + sin * (-0.283f), 0f, 0f,
        rw + cos * (-rw) + sin * (-(1f - rw)), gw + cos * (-gw) + sin * gw, bw + cos * (1f - bw) + sin * bw, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

// The web camera page's CSS filter chain per effect id, as one
// ColorMatrix. Functions are applied left-to-right like CSS (the first
// in the chain runs first).
private fun camFilterColorMatrix(id: String): ColorMatrix {
    var m = camMatIdentity()
    fun apply(next: FloatArray) { m = camMatMul(next, m) }
    when (id) {
        "vivid" -> { apply(camMatSaturation(1.5f)); apply(camMatContrast(1.12f)) }
        "warm" -> { apply(camMatSepia(0.3f)); apply(camMatSaturation(1.4f)) }
        "cool" -> { apply(camMatSaturation(1.1f)); apply(camMatHueRotate(-15f)) }
        "beauty" -> { apply(camMatBrightness(1.08f)); apply(camMatSaturation(1.2f)); apply(camMatContrast(1.02f)) }
        "bw" -> { apply(camMatSaturation(0f)); apply(camMatContrast(1.15f)) }
        "vintage" -> { apply(camMatSepia(0.5f)); apply(camMatSaturation(0.8f)); apply(camMatContrast(0.95f)) }
        "sunset" -> { apply(camMatSepia(0.4f)); apply(camMatSaturation(1.6f)); apply(camMatHueRotate(-20f)) }
        "neon" -> { apply(camMatSaturation(2f)); apply(camMatContrast(1.3f)) }
        "dreamy" -> { apply(camMatBrightness(1.15f)); apply(camMatSaturation(0.9f)); apply(camMatContrast(0.9f)) }
        "noir" -> { apply(camMatSaturation(0f)); apply(camMatContrast(1.4f)); apply(camMatBrightness(0.9f)) }
        "golden" -> { apply(camMatSepia(0.6f)); apply(camMatSaturation(1.3f)); apply(camMatBrightness(1.05f)) }
    }
    return ColorMatrix(m)
}

// v5.20 (M4): bake a camera effect into a captured JPEG, like the
// web's capturePhoto (ctx.filter = activeEffect.css). Decodes the file,
// draws it through the effect's ColorMatrix (with EXIF rotation applied
// so a portrait photo stays portrait), re-encodes JPEG 90. Returns the
// baked file's Uri, or null to keep the original file on any failure —
// the capture must never fail because of the bake. "normal" needs no
// work, so the original file is returned untouched.
private fun bakeEffectIntoPhoto(context: Context, file: File, effectId: String): Uri? {
    if (effectId == "normal") return Uri.fromFile(file)
    return try {
        val matrix = camFilterColorMatrix(effectId)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Downscale only if huge (a 12MP photo is ~48MB decoded); keep
        // full resolution otherwise so the cover stays sharp.
        var sample = 1
        while (bounds.outWidth / sample > 3000 || bounds.outHeight / sample > 3000) sample *= 2
        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val src = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        val rotation = try {
            when (android.media.ExifInterface(file.absolutePath).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        val oriented = if (rotation != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(rotation) }
            val r = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            src.recycle()
            r
        } else src
        val out = android.graphics.Bitmap.createBitmap(oriented.width, oriented.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(out)
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(matrix.values))
            }
            canvas.drawBitmap(oriented, 0f, 0f, paint)
        } finally {
            oriented.recycle()
        }
        val baked = File(context.cacheDir, "reel_photo_effect_${System.currentTimeMillis()}.jpg")
        var ok = false
        try {
            baked.outputStream().use { stream -> ok = out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream) }
        } finally {
            out.recycle()
        }
        if (!ok || baked.length() <= 0) {
            try { baked.delete() } catch (_: Exception) {}
            return null
        }
        try { file.delete() } catch (_: Exception) {}
        Uri.fromFile(baked)
    } catch (_: Exception) {
        null
    }
}

// Web CAMERA_EFFECTS (src/app/reels/camera/page.tsx), in order. server
// is the upload filter the effect maps to (B-B29: only 7 distinct
// server filters exist; several camera effects share one).
private data class CamEffect(val id: String, val name: String, val cat: String, val server: String)

private val CAM_EFFECTS = listOf(
    CamEffect("normal", "Normal", "trending", "normal"),
    CamEffect("vivid", "Vivid", "trending", "vivid"),
    CamEffect("warm", "Warm Glow", "trending", "warm"),
    CamEffect("cool", "Cool Tone", "trending", "cool"),
    CamEffect("beauty", "Beauty", "trending", "beauty"),
    CamEffect("bw", "Mono", "trending", "bw"),
    CamEffect("vintage", "Vintage", "trending", "vintage"),
    CamEffect("sunset", "Sunset", "trending", "warm"),
    CamEffect("neon", "Neon Pop", "games", "vivid"),
    CamEffect("dreamy", "Dreamy", "games", "beauty"),
    CamEffect("noir", "Noir", "games", "bw"),
    CamEffect("golden", "Golden", "games", "warm"),
)

private val CAM_EFFECT_CATS = listOf("trending" to "Trending", "games" to "Games")
private val CAM_DURATIONS = listOf(15 to "15s", 60 to "60s", 180 to "3m")
private val CAM_TIMER_OPTIONS = listOf("Off" to 0, "3s" to 3, "10s" to 10)
private val CAM_SPEEDS = listOf("0.5x" to "0.5", "1x" to "1", "1.5x" to "1.5", "2x" to "2")

// v4.7 (owner): ripple-free tap for the reels screen — local copy of the
// Screens2.kt helper (it is private to that file). Compose's default
// clickable draws a grey ripple on press; on the dark camera preview
// that reads as a dirty shadow. Same pattern: a remembered
// MutableInteractionSource with indication = null.
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

@Composable
fun ReelCameraScreen(
    onVideoRecorded: (Uri, String, String) -> Unit,
    onPhotoCaptured: (Uri) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    // B-B16: camera and microphone are tracked SEPARATELY. Photo mode
    // needs only the camera; only video recording needs the mic. A
    // permanently-denied permission offers app Settings (re-launching the
    // system request is silently ignored by Android once denied forever).
    var cameraGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var micGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var permAsked by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    fun permanentlyDenied(perm: String): Boolean {
        val act = activity ?: return false
        return permAsked && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, perm)
    }
    fun openAppSettings() {
        try {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) { error = "Open Settings > Apps > Frianzo > Permissions to allow the camera" }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permAsked = true
        cameraGranted = res[Manifest.permission.CAMERA] == true
        micGranted = res[Manifest.permission.RECORD_AUDIO] == true
    }
    LaunchedEffect(Unit) {
        // v5.10: only ask when a permission is actually missing. Before,
        // the requester ran on EVERY open and cameraGranted started false,
        // so the "Allow camera" screen flashed for <1s even though the
        // permission was already granted (owner's recording). Now an
        // already-granted permission opens straight to the preview.
        if (!cameraGranted || !micGranted) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // v5.19 (H8): default is FRONT (web reels/camera opens on the front
    // camera). The flip toggle below still switches BACK <-> FRONT.
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    // B-B17: an error banner clears itself after 5s (and is cleared by a
    // successful record/photo, above) instead of staying pinned all session.
    LaunchedEffect(error) {
        if (error != null) { delay(5000); error = null }
    }
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ try { cameraProvider = future.get() } catch (_: Exception) { error = "Camera unavailable on this phone" } }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose { try { cameraProvider?.unbindAll() } catch (_: Exception) {} }
    }

    val preview = remember { Preview.Builder().build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val recorder = remember { Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build() }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    LaunchedEffect(cameraProvider, lensFacing) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val owner = lifecycleOwner ?: return@LaunchedEffect
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, selector, preview, videoCapture, imageCapture)
        } catch (_: Exception) { error = "Camera unavailable on this phone" }
    }

    // Recorder state.
    var mode by remember { mutableStateOf("video") } // video | photo
    var maxDurationSec by remember { mutableStateOf(60) } // web default is 60s (was 15s)
    var speed by remember { mutableStateOf("1") }
    var effectId by remember { mutableStateOf("normal") } // camera effect id (ColorMatrix)
    var serverFilter by remember { mutableStateOf("normal") }
    var timerSecs by remember { mutableStateOf(0) } // 0 = Off, else 3 or 10 (web TIMER_OPTIONS)
    var timerOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var effectsOpen by remember { mutableStateOf(false) }
    var effectCat by remember { mutableStateOf("trending") }
    var torchOn by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var startMs by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val recording = activeRecording != null
    val activeEffect = CAM_EFFECTS.firstOrNull { it.id == effectId } ?: CAM_EFFECTS[0]
    val selectEffect: (CamEffect) -> Unit = { ef -> effectId = ef.id; serverFilter = ef.server }

    // Preview hint tint for the chosen effect (the real effect is applied
    // by the server at upload — see the file header). Keyed by the
    // server filter, so Sunset/Golden share warm, Neon shares vivid,
    // Dreamy shares beauty, Noir shares bw.
    val tint = when (serverFilter) {
        "warm" -> Color(0xFFF97316).copy(alpha = 0.12f)
        "cool" -> Color(0xFF3B82F6).copy(alpha = 0.12f)
        "beauty" -> Color(0xFFF9A8D4).copy(alpha = 0.10f)
        "bw" -> Color.Gray.copy(alpha = 0.18f)
        "vintage" -> Color(0xFFB45309).copy(alpha = 0.14f)
        "vivid" -> Color(0xFFEC4899).copy(alpha = 0.08f)
        else -> Color.Transparent
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onVideoRecorded(uri, serverFilter, speed)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPhotoCaptured(uri)
    }

    val startRecording: () -> Unit = {
        // B-B16: video needs the mic; without it, ask once (or point at
        // Settings when permanently denied) instead of failing silently.
        // Photo mode is unaffected — it never reaches this function.
        if (!micGranted) {
            if (permanentlyDenied(Manifest.permission.RECORD_AUDIO)) error = "Microphone is off — open Settings to allow it, or switch to Photo mode"
            else { error = "Microphone is needed for video — allow it, or switch to Photo mode"; permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
        } else {
            val file = File(context.cacheDir, "reel_${System.currentTimeMillis()}.mp4")
            try {
                val rec = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            activeRecording = null
                            if (!event.hasError()) { error = null; onVideoRecorded(Uri.fromFile(file), serverFilter, speed) }
                            else error = "Recording failed — try again"
                        }
                    }
                activeRecording = rec
            } catch (_: Exception) { error = "Could not start recording" }
        }
    }
    val takePhoto: () -> Unit = {
        val file = File(context.cacheDir, "reel_photo_${System.currentTimeMillis()}.jpg")
        try {
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        error = null
                        // v5.20 (M4): bake the chosen effect into the
                        // photo, like the web's capturePhoto — the
                        // effect is no longer silently dropped. The
                        // photo still routes as the reel cover (see
                        // Screens2.kt onPhotoCaptured); a photo cannot
                        // be reel media — the editor + uploadReel are
                        // video-only (video part + durationSec).
                        onPhotoCaptured(bakeEffectIntoPhoto(context, file, effectId) ?: Uri.fromFile(file))
                    }
                    override fun onError(exception: ImageCaptureException) { error = "Photo failed — try again" }
                }
            )
        } catch (_: Exception) { error = "Could not take photo" }
    }

    // Countdown, then record.
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
            if (countdown == 0) startRecording()
        }
    }
    // Record timer + auto-stop at the chosen max duration.
    LaunchedEffect(activeRecording) {
        if (activeRecording != null) {
            startMs = System.currentTimeMillis()
            while (activeRecording != null) {
                delay(100)
                elapsedMs = System.currentTimeMillis() - startMs
                if (elapsedMs >= maxDurationSec * 1000L) { activeRecording?.stop(); break }
            }
        } else elapsedMs = 0L
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val third = maxHeight / 3
        val sheetMaxH = maxHeight * 0.7f
        val gridMaxH = maxHeight * 0.4f

        if (cameraGranted && cameraProvider != null) {
            AndroidView(
                factory = { previewView.also { preview.setSurfaceProvider(it.surfaceProvider) } },
                modifier = Modifier.fillMaxSize(),
            )
            if (tint != Color.Transparent) Box(Modifier.fillMaxSize().background(tint))
        } else if (!cameraGranted) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (permanentlyDenied(Manifest.permission.CAMERA)) "Camera permission is off. Turn it on in Settings to use the camera." else "Camera is needed to record a reel or take a photo. (Microphone is only needed for video.)", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                if (permanentlyDenied(Manifest.permission.CAMERA)) {
                    Box(Modifier.clip(RoundedCornerShape(28.dp)).background(Color.White).noRippleClickable { openAppSettings() }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text("Open Settings", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Box(Modifier.clip(RoundedCornerShape(28.dp)).background(Color.White).noRippleClickable { permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text("Allow camera", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.noRippleClickable { onClose() }.padding(8.dp)) { Text("Close", color = Color.White, fontSize = 15.sp) }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Starting camera…", color = Color.White, fontSize = 15.sp) }
        }

        if (cameraGranted) {
            // Top bar: X left, flash right ONLY (web). No Timer/Flip here.
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).noRippleClickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    Box(Modifier.size(40.dp).noRippleClickable {
                        torchOn = !torchOn
                        try { camera?.cameraControl?.enableTorch(torchOn) } catch (_: Exception) {}
                    }, contentAlignment = Alignment.Center) {
                        Icon(if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, contentDescription = "Flash", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                } else {
                    Spacer(Modifier.size(40.dp))
                }
            }

            // Recording timer + progress at the TOP (web: top-16).
            if (recording) {
                Column(Modifier.align(Alignment.TopCenter).padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                        Text(
                            "${formatRecTime(elapsedMs)} / ${formatRecTime(maxDurationSec * 1000L)}",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(192.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth((elapsedMs.toFloat() / (maxDurationSec * 1000f)).coerceIn(0f, 1f)).height(4.dp).background(Color(0xFFEF4444)))
                    }
                }
            }

            // Error banner (web: red 90%, rounded 12dp, top-20, margins 16dp).
            if (error != null) {
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 80.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0xFFEF4444).copy(alpha = 0.9f)).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(error!!, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }

            // Duration pill (web: bottom-236dp, black/40 pill, 13sp semibold).
            // v5.3 (owner): the pill was sitting almost on top of the effects
            // row (only ~6dp gap) so it read as sinking into the effects.
            // Lifted to bottom-248dp for a clear gap above the effects row,
            // so the pill stays above the effects and the design cannot crowd.
            if (!recording && !effectsOpen) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 248.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f)).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CAM_DURATIONS.forEach { (v, label) ->
                        val sel = maxDurationSec == v
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color.Transparent)
                                .noRippleClickable { if (!recording) maxDurationSec = v }.padding(horizontal = 16.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = if (sel) Color.Black else Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // Effects row: circular face thumbnails (web: bottom-152dp).
            if (!recording && !effectsOpen) {
                LazyRow(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 152.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp),
                ) {
                    items(CAM_EFFECTS) { ef ->
                        val sel = effectId == ef.id
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.size(56.dp).clip(CircleShape)
                                    .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                                    .noRippleClickable { selectEffect(ef) },
                            ) {
                                FoundationImage(
                                    painter = painterResource(R.drawable.effect_preview_face),
                                    contentDescription = ef.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    colorFilter = if (ef.id == "normal") null else ColorFilter.colorMatrix(camFilterColorMatrix(ef.id)),
                                )
                            }
                            Text(
                                ef.name, color = if (sel) Color.White else Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 64.dp),
                            )
                        }
                    }
                }
            }

            // Right toolbar (web: right-3, top-1/3): Timer, Speed, Photo mode.
            if (!recording && countdown == 0) {
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = third, end = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.noRippleClickable { timerOpen = !timerOpen; speedOpen = false },
                    ) {
                        Icon(Icons.Filled.Timer, contentDescription = "Timer", tint = Color.White, modifier = Modifier.size(22.dp))
                        Text(if (timerSecs > 0) "${timerSecs}s" else "Timer", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.noRippleClickable { speedOpen = !speedOpen; timerOpen = false },
                    ) {
                        Icon(Icons.Filled.FlashOn, contentDescription = "Speed", tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("${speed}x", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.noRippleClickable { if (!recording) mode = if (mode == "video") "photo" else "video" },
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Photo mode", tint = Color.White, modifier = Modifier.size(22.dp))
                        Text(if (mode == "photo") "Photo" else "Video", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                    }
                }
            }

            // Timer popup (web: right-16, top-1/3, black/70, rounded 16dp).
            if (timerOpen && !recording) {
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = third, end = 64.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(8.dp),
                ) {
                    CAM_TIMER_OPTIONS.forEach { (label, value) ->
                        val sel = timerSecs == value
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) Color.White else Color.Transparent)
                                .noRippleClickable { timerSecs = value; timerOpen = false }.padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = if (sel) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Speed popup (same position/style as the timer popup).
            if (speedOpen && !recording) {
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = third, end = 64.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(8.dp),
                ) {
                    CAM_SPEEDS.forEach { (label, value) ->
                        val sel = speed == value
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) Color.White else Color.Transparent)
                                .noRippleClickable { speed = value; speedOpen = false }.padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = if (sel) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Bottom controls (web: bottom-0, pb-8, pt-4).
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(top = 16.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Gallery pick.
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.15f))
                            .noRippleClickable { if (mode == "video") pickVideo.launch("video/*") else pickPhoto.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = "Pick from gallery", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    // Record / capture — 76dp outer circle, 5dp white border.
                    Box(
                        Modifier.size(76.dp).border(5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                            .noRippleClickable {
                                if (recording) { activeRecording?.stop() }
                                else if (mode == "photo") { takePhoto() }
                                else if (timerSecs > 0) { countdown = timerSecs }
                                else { startRecording() }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mode == "photo") Box(Modifier.size(60.dp).clip(CircleShape).background(Color.White))
                        else if (recording) Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
                        else Box(Modifier.size(60.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                    }
                    // Flip camera.
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.15f))
                            .noRippleClickable {
                                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                                torchOn = false
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "Flip camera", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                // All effects button (web: below the record row, 12dp gap).
                if (!recording) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f))
                            .noRippleClickable { effectsOpen = true }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("All effects", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // All Effects sheet (web: overlay black/60, sheet max 70%, #1c1c1e).
            if (effectsOpen) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).noRippleClickable { effectsOpen = false })
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = sheetMaxH)
                            .background(Color(0xFF1C1C1E), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(16.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(activeEffect.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("Created by: Frianzo", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f))
                                    .noRippleClickable { effectsOpen = false },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close effects", tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            CAM_EFFECT_CATS.forEach { (id, name) ->
                                val sel = effectCat == id
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.noRippleClickable { effectCat = id }.padding(bottom = 8.dp),
                                ) {
                                    Text(name, color = if (sel) Color.White else Color.White.copy(alpha = 0.4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(4.dp))
                                    Box(Modifier.size(width = 32.dp, height = 2.dp).clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color.Transparent))
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                        Spacer(Modifier.height(12.dp))
                        val shown = CAM_EFFECTS.filter { it.cat == effectCat }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = gridMaxH),
                        ) {
                            items(shown.size) { i ->
                                val ef = shown[i]
                                val sel = effectId == ef.id
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.noRippleClickable { selectEffect(ef); effectsOpen = false },
                                ) {
                                    Box(
                                        Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))
                                            .border(if (sel) 3.dp else 0.dp, Color.White, RoundedCornerShape(16.dp)),
                                    ) {
                                        FoundationImage(
                                            painter = painterResource(R.drawable.effect_preview_face),
                                            contentDescription = ef.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            colorFilter = if (ef.id == "normal") null else ColorFilter.colorMatrix(camFilterColorMatrix(ef.id)),
                                        )
                                    }
                                    Text(
                                        ef.name, color = if (sel) Color.White else Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                        modifier = Modifier.widthIn(max = 80.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Countdown overlay (web: full overlay black/40, 128sp number).
            if (countdown > 0) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    Text("$countdown", color = Color.White, fontSize = 128.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

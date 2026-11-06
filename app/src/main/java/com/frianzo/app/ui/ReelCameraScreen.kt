// ---------- IN-APP CAMERA RECORDER (v3.8, F1): TikTok-style recorder —
// 15s / 60s / 3m picker, 12 effects, flash, front/back flip, countdown
// timer, speed, Photo mode, gallery pick, progress + timer, auto-stop.
// CameraX (no system camera intent). Honest limit: the 12 effects are
// chosen here and applied by the SERVER at upload (the server filter
// enum); there is no real-time GPU processing of the preview — the tint
// overlay is only a preview hint of the chosen effect. Speed is likewise
// an upload value, not a live preview speed.
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.delay

private fun formatRecTime(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun ReelCameraScreen(
    onVideoRecorded: (Uri, String, String) -> Unit,
    onPhotoCaptured: (Uri) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var permGranted by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permGranted = res.values.all { it }
    }
    LaunchedEffect(Unit) {
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ try { cameraProvider = future.get() } catch (_: Exception) { error = "Camera unavailable on this phone" } }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose { try { cameraProvider?.unbindAll() } catch (_: Exception) {} }
    }

    val preview = remember { Preview.Builder().build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val recorder = remember { Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build() }
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
    var maxDurationSec by remember { mutableStateOf(15) }
    var speed by remember { mutableStateOf("1") }
    var effect by remember { mutableStateOf("Normal") }
    var serverFilter by remember { mutableStateOf("normal") }
    var timerOn by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var startMs by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val recording = activeRecording != null

    val effects = listOf(
        "Normal" to "normal", "Vivid" to "vivid", "Warm Glow" to "warm",
        "Cool Tone" to "cool", "Beauty" to "beauty", "Mono" to "bw",
        "Vintage" to "vintage", "Sunset" to "warm", "Neon Pop" to "vivid",
        "Dreamy" to "beauty", "Noir" to "bw", "Golden" to "warm",
    )
    // Preview hint tint for the chosen effect (the real effect is applied
    // by the server at upload — see the file header).
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
        val file = File(context.cacheDir, "reel_${System.currentTimeMillis()}.mp4")
        try {
            val rec = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        activeRecording = null
                        if (!event.hasError()) onVideoRecorded(Uri.fromFile(file), serverFilter, speed)
                        else error = "Recording failed — try again"
                    }
                }
            activeRecording = rec
        } catch (_: Exception) { error = "Could not start recording" }
    }
    val takePhoto: () -> Unit = {
        val file = File(context.cacheDir, "reel_photo_${System.currentTimeMillis()}.jpg")
        try {
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) { onPhotoCaptured(Uri.fromFile(file)) }
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permGranted && cameraProvider != null) {
            AndroidView(
                factory = { previewView.also { preview.setSurfaceProvider(it.surfaceProvider) } },
                modifier = Modifier.fillMaxSize(),
            )
            if (tint != Color.Transparent) Box(Modifier.fillMaxSize().background(tint))
        } else if (!permGranted) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Camera and microphone are needed to record a reel.", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(Color.White).clickable { permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("Allow camera", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.clickable { onClose() }.padding(8.dp)) { Text("Close", color = Color.White, fontSize = 15.sp) }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Starting camera…", color = Color.White, fontSize = 15.sp) }
        }

        if (permGranted) {
            // Top bar: close, countdown timer, flash, flip — 40dp dark
            // circles with ~20dp glyphs (live-web sizes).
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (timerOn) Color.White else Color.Black.copy(alpha = 0.45f)).clickable { timerOn = !timerOn }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Timer, contentDescription = "3 second countdown", tint = if (timerOn) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable {
                        torchOn = !torchOn
                        try { camera?.cameraControl?.enableTorch(torchOn) } catch (_: Exception) {}
                    }, contentAlignment = Alignment.Center) {
                        Icon(if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, contentDescription = "Flash", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    torchOn = false
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "Flip camera", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            // Countdown number.
            if (countdown > 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("$countdown", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Bottom controls.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (mode == "video") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatRecTime(elapsedMs), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f).height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                            Box(Modifier.fillMaxWidth((elapsedMs.toFloat() / (maxDurationSec * 1000f)).coerceIn(0f, 1f)).height(3.dp).background(Color.White))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(formatRecTime(maxDurationSec * 1000L), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    // Duration + speed pickers.
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(15 to "15s", 60 to "60s", 180 to "3m")) { (v, label) ->
                            val sel = maxDurationSec == v && !recording
                            Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) Color.White else Color.White.copy(alpha = 0.2f)).clickable { if (!recording) maxDurationSec = v }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                            }
                        }
                        items(listOf("0.5" to "0.5×", "1" to "1×", "1.5" to "1.5×", "2" to "2×")) { (v, label) ->
                            val sel = speed == v && !recording
                            Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) Color.White else Color.White.copy(alpha = 0.2f)).clickable { if (!recording) speed = v }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // 12 effects (web list) — the chosen one is applied by the
                // server at upload; the preview tint is only a hint.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(effects) { (label, server) ->
                        val sel = effect == label
                        Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) Color.White else Color.White.copy(alpha = 0.2f)).clickable { effect = label; serverFilter = server }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Gallery pick.
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable {
                        if (mode == "video") pickVideo.launch("video/*") else pickPhoto.launch("image/*")
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Image, contentDescription = "Pick from gallery", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    // Record / stop — 72dp.
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFEF4444)).clickable {
                        if (recording) { activeRecording?.stop() }
                        else if (mode == "photo") { takePhoto() }
                        else if (timerOn) { countdown = 3 }
                        else { startRecording() }
                    }, contentAlignment = Alignment.Center) {
                        if (recording) Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
                        else Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFFDC2626)))
                    }
                    Spacer(Modifier.weight(1f))
                    // Photo / Video mode.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Video", color = if (mode == "video") Color.White else Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = if (mode == "video") FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable { if (!recording) mode = "video" }.padding(4.dp))
                        Text("Photo", color = if (mode == "photo") Color.White else Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = if (mode == "photo") FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable { if (!recording) mode = "photo" }.padding(4.dp))
                    }
                }
            }
        }

        if (error != null) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 64.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(error!!, color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

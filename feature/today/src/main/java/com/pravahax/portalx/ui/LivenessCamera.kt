package com.pravahax.portalx.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.pravahax.portalx.media.FaceFrame
import com.pravahax.portalx.media.LivenessCheck
import java.io.File
import java.util.concurrent.Executors

sealed interface LivenessOutcome {
    data class Passed(val file: File, val challenges: List<String>) : LivenessOutcome
    data object Cancelled : LivenessOutcome
    /** The check can't run here (no front camera, no Play services face model, camera error): fall back to the system camera. */
    data object Unavailable : LivenessOutcome
}

object LivenessSupport {
    /** Front camera + Google Play services (the face model is delivered by Play services, not bundled). */
    fun available(ctx: Context): Boolean = runCatching {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT) &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
}

/** v0.9: full-screen front-camera liveness check; on success captures the selfie to [output]. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun LivenessDialog(output: File, onResult: (LivenessOutcome) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var attempt by remember { mutableIntStateOf(0) }
    val check = remember(attempt) { LivenessCheck(startedAt = System.currentTimeMillis()) }
    var state by remember { mutableStateOf<LivenessCheck.State>(check.state) }
    LaunchedEffect(attempt) { state = check.state }
    var capturing by remember { mutableStateOf(false) }
    val done = remember { booleanArrayOf(false) }
    fun finish(o: LivenessOutcome) { if (!done[0]) { done[0] = true; onResult(o) } }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember {
        FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).setMinFaceSize(0.15f).build())
    }
    val main = remember { ContextCompat.getMainExecutor(ctx) }
    val currentCheck by rememberUpdatedState(check)

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get().also { provider = it }
                if (!p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) { finish(LivenessOutcome.Unavailable); return@addListener }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || done[0]) { proxy.close(); return@setAnalyzer }
                    val rot = proxy.imageInfo.rotationDegrees
                    val width = if (rot == 90 || rot == 270) proxy.height else proxy.width
                    detector.process(InputImage.fromMediaImage(media, rot))
                        .addOnSuccessListener(main) { faces ->
                            val f = faces.firstOrNull()
                            val frame = FaceFrame(faces.size, (f?.boundingBox?.width() ?: 0) / width.toFloat(), f?.headEulerAngleY ?: 0f,
                                f?.leftEyeOpenProbability, f?.rightEyeOpenProbability, f?.smilingProbability)
                            state = currentCheck.onFrame(frame, System.currentTimeMillis())
                        }
                        .addOnFailureListener(main) { e -> if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) finish(LivenessOutcome.Unavailable) }
                        .addOnCompleteListener { proxy.close() }
                }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, capture)
            } catch (e: Exception) {
                finish(LivenessOutcome.Unavailable)
            }
        }, main)
        onDispose {
            runCatching { provider?.unbindAll() }
            runCatching { detector.close() }
            executor.shutdown()
        }
    }

    LaunchedEffect(state) {
        if (state == LivenessCheck.State.Passed && !capturing) {
            capturing = true
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(output).build(), executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) { main.execute { finish(LivenessOutcome.Passed(output, check.challenges.map { it.name.lowercase() })) } }
                override fun onError(e: ImageCaptureException) { main.execute { capturing = false; finish(LivenessOutcome.Unavailable) } }
            })
        }
    }

    Dialog(onDismissRequest = { finish(LivenessOutcome.Cancelled) }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Kicker("Quick liveness check")
            Spacer(Modifier.height(Space.lg))
            val s = state
            val ring = when (s) { LivenessCheck.State.Passed -> PortalTheme.status.success; is LivenessCheck.State.Failed -> PortalTheme.status.danger; else -> MaterialTheme.colorScheme.primary }
            AndroidView({ previewView }, Modifier.size(260.dp).clip(CircleShape).border(4.dp, ring, CircleShape))
            Spacer(Modifier.height(Space.xl))
            val text = when (s) {
                is LivenessCheck.State.Hint -> s.text
                is LivenessCheck.State.Doing -> s.challenge.prompt
                LivenessCheck.State.Passed -> "Done — taking your selfie"
                is LivenessCheck.State.Failed -> s.reason
            }
            Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                check.challenges.indices.forEach { i ->
                    val reached = s == LivenessCheck.State.Passed || (s is LivenessCheck.State.Doing && i < s.index)
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (reached) PortalTheme.status.success else MaterialTheme.colorScheme.outlineVariant))
                }
            }
            Spacer(Modifier.height(Space.xxl))
            if (s is LivenessCheck.State.Failed) Button(onClick = { attempt++ }, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { Text("Try again") }
            TextButton(onClick = { finish(LivenessOutcome.Cancelled) }, enabled = !capturing) { Text("Cancel") }
        }
    }
}

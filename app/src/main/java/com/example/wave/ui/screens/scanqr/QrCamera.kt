package com.example.wave.ui.screens.scanqr

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Live back-camera preview (CameraX) that feeds every frame to [analyzer] (the ML Kit QR reader).
 * [torchOn] switches the flashlight; [onTorchAvailable] reports whether this camera has one.
 * The camera is released when this leaves the screen (torch goes off with it).
 */
@Composable
fun QrCameraPreview(
    torchOn: Boolean,
    analyzer: ImageAnalysis.Analyzer,
    onTorchAvailable: (Boolean) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // a TextureView, so rounded clipping works
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val torchAvailable by rememberUpdatedState(onTorchAvailable)
    val cameraError by rememberUpdatedState(onCameraError)

    DisposableEffect(lifecycleOwner, analyzer) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get().also { provider = it }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }
                p.unbindAll()
                val bound = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = bound
                torchAvailable(bound.cameraInfo.hasFlashUnit())
            } catch (e: Exception) { // no back camera, or the camera is in use by another app
                camera = null
                torchAvailable(false)
                cameraError()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            camera = null
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(camera, torchOn) { camera?.cameraControl?.enableTorch(torchOn) }

    AndroidView(factory = { previewView }, modifier = modifier)
}

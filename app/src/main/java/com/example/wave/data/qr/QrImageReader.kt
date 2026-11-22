package com.example.wave.data.qr

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Reads QR codes with ML Kit's bundled model (offline): from live camera frames ([analyzer]) and from a
 * picture the user picks ([readFromImage]). Returns the code's raw text; checking it is QrVerifier's job.
 */
class QrImageReader(context: Context) {
    private val app = context.applicationContext
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    /** CameraX analyzer: calls [onFound] with the first readable QR text in a frame. Frames are dropped while busy. */
    fun analyzer(onFound: (String) -> Unit): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { proxy -> analyze(proxy, onFound) }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy, onFound: (String) -> Unit) {
        val media = proxy.image ?: return proxy.close()
        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onFound) }
            .addOnCompleteListener { proxy.close() }
    }

    /** The QR text in a picture, or null if there's no readable QR code (or the picture can't be opened). */
    suspend fun readFromImage(uri: Uri): String? {
        val image = withContext(Dispatchers.IO) {
            try { InputImage.fromFilePath(app, uri) } catch (_: IOException) { null }
        } ?: return null
        return suspendCancellableCoroutine { cont ->
            scanner.process(image)
                .addOnSuccessListener { codes -> cont.resume(codes.firstNotNullOfOrNull { it.rawValue }) }
                .addOnFailureListener { cont.resume(null) }
        }
    }
}

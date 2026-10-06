package dj.sentia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Prend UNE photo (caméra arrière, sans aperçu) quand l'IA le demande. Rien n'est enregistré dans la galerie. */
object CameraCapture {

    /** @return la photo JPEG en base64 (sans préfixe). */
    suspend fun capture(context: Context, owner: LifecycleOwner, highRes: Boolean): String {
        val executor = ContextCompat.getMainExecutor(context)
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val f = ProcessCameraProvider.getInstance(context)
            f.addListener({
                try { cont.resume(f.get()) } catch (e: Exception) { cont.resumeWithException(e) }
            }, executor)
        }
        val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
            delay(1000) // le temps que l'exposition et la mise au point se stabilisent (jamais de photo avant que la caméra soit prête)
            suspend fun shoot(): ImageProxy = suspendCancellableCoroutine { cont ->
                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image) }
                    override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
                })
            }
            var photo = shoot().use { toBase64(it, if (highRes) 1600 else 1280) }
            // Image quasi noire (caméra pas encore prête, objectif couvert) : une seule nouvelle tentative, après une courte pause.
            if (photo.second < MIN_BRIGHTNESS) {
                delay(700)
                photo = shoot().use { toBase64(it, if (highRes) 1600 else 1280) }
            }
            return photo.first
        } finally {
            provider.unbindAll()
        }
    }

    /** @return (JPEG en base64, luminosité moyenne 0-255 de l'image) */
    private fun toBase64(image: ImageProxy, maxSide: Int): Pair<String, Int> {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("decode")
        val rotation = image.imageInfo.rotationDegrees
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (rotation != 0 || scale < 1f) {
            val m = Matrix()
            if (rotation != 0) m.postRotate(rotation.toFloat())
            if (scale < 1f) m.postScale(scale, scale)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = ByteArrayOutputStream()
        var quality = 75
        do {
            out.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            quality -= 10
        } while (out.size() > 1_200_000 && quality > 30) // le serveur refuse au-delà de ~1,3 Mo
        // Luminosité moyenne sur une petite grille de points (image noire = caméra inactive).
        var sum = 0L
        var n = 0
        for (yy in 0 until 12) for (xx in 0 until 12) {
            val c = bmp.getPixel((bmp.width - 1) * xx / 11, (bmp.height - 1) * yy / 11)
            sum += (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
            n++
        }
        return Pair(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), (sum / n).toInt())
    }

    private const val MIN_BRIGHTNESS = 8
}

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
            delay(800) // le temps que l'exposition et la mise au point se stabilisent
            val proxy = suspendCancellableCoroutine<ImageProxy> { cont ->
                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image) }
                    override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
                })
            }
            return proxy.use { toBase64(it, if (highRes) 1600 else 1280) }
        } finally {
            provider.unbindAll()
        }
    }

    private fun toBase64(image: ImageProxy, maxSide: Int): String {
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
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

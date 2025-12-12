package appdevforall.codeonthego.computervision.data.source
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
class OcrSource {

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeText(bitmap: Bitmap): Result<List<Text.TextBlock>> =
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    continuation.resume(Result.success(visionText.textBlocks))
                }
                .addOnFailureListener { exception ->
                    continuation.resume(Result.failure(exception))
                }
        }
}
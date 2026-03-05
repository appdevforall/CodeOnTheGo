package org.appdevforall.codeonthego.computervision.ui

import android.graphics.Bitmap
import android.net.Uri
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

data class ComputerVisionUiState(
    val currentBitmap: Bitmap? = null,
    val imageUri: Uri? = null,
    val detections: List<DetectionResult> = emptyList(),
    val visualizedBitmap: Bitmap? = null,
    val layoutFilePath: String? = null,
    val layoutFileName: String? = null,
    val isModelInitialized: Boolean = false,
    val currentOperation: CvOperation = CvOperation.Idle,
    val leftGuidePct: Float = 0.2f,
    val rightGuidePct: Float = 0.8f,
    val parsedAnnotations: Map<String, String> = emptyMap() // Replaced old marginAnnotations
) {
    val hasImage: Boolean
        get() = currentBitmap != null

    val hasDetections: Boolean
        get() = detections.isNotEmpty()

    val canRunDetection: Boolean
        get() = hasImage && isModelInitialized && currentOperation == CvOperation.Idle

    val canGenerateXml: Boolean
        get() = hasDetections && currentOperation == CvOperation.Idle
}

sealed class CvOperation {
    object Idle : CvOperation()
    object InitializingModel : CvOperation()
    object RunningYolo : CvOperation()
    object RunningOcr : CvOperation()
    object MergingDetections : CvOperation()
    object GeneratingXml : CvOperation()
    object SavingFile : CvOperation()
}

sealed class ComputerVisionEffect {
    object OpenImagePicker : ComputerVisionEffect()
    object RequestCameraPermission : ComputerVisionEffect()
    data class LaunchCamera(val outputUri: Uri) : ComputerVisionEffect()
    data class ShowToast(val messageResId: Int) : ComputerVisionEffect()
    data class ShowError(val message: String) : ComputerVisionEffect()
    data class ShowConfirmDialog(val fileName: String) : ComputerVisionEffect()
    data class ReturnXmlResult(val xml: String) : ComputerVisionEffect()
    data class FileSaved(val fileName: String) : ComputerVisionEffect()
    object NavigateBack : ComputerVisionEffect()
}

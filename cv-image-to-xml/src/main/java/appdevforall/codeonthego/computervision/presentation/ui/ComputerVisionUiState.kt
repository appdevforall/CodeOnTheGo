package appdevforall.codeonthego.computervision.presentation.ui

import android.graphics.Bitmap
import android.net.Uri
import appdevforall.codeonthego.computervision.domain.model.DetectionResult


data class ComputerVisionUiState(
    val currentBitmap: Bitmap? = null,
    val imageUri: Uri? = null,
    val detections: List<DetectionResult> = emptyList(),
    val visualizedBitmap: Bitmap? = null,
    val layoutFilePath: String? = null,
    val layoutFileName: String? = null,
    val isModelInitialized: Boolean = false,
    val currentOperation: CvOperation = CvOperation.Idle
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
    data object Idle : CvOperation()
    data object InitializingModel : CvOperation()
    data object RunningYolo : CvOperation()
    data object RunningOcr : CvOperation()
    data object MergingDetections : CvOperation()
    data object GeneratingXml : CvOperation()
    data object SavingFile : CvOperation()
}

sealed class ComputerVisionEvent {
    data class ImageSelected(val uri: Uri) : ComputerVisionEvent()
    data class ImageCaptured(val uri: Uri, val success: Boolean) : ComputerVisionEvent()
    data object RunDetection : ComputerVisionEvent()
    data object UpdateLayoutFile : ComputerVisionEvent()
    data object ConfirmUpdate : ComputerVisionEvent()
    data object SaveToDownloads : ComputerVisionEvent()
    data object OpenImagePicker : ComputerVisionEvent()
    data object RequestCameraPermission : ComputerVisionEvent()
}

sealed class ComputerVisionEffect {
    data object OpenImagePicker : ComputerVisionEffect()
    data object RequestCameraPermission : ComputerVisionEffect()
    data class LaunchCamera(val outputUri: Uri) : ComputerVisionEffect()
    data class ShowToast(val messageResId: Int) : ComputerVisionEffect()
    data class ShowError(val message: String) : ComputerVisionEffect()
    data class ShowConfirmDialog(val fileName: String) : ComputerVisionEffect()
    data class ReturnXmlResult(val xml: String) : ComputerVisionEffect()
    data class FileSaved(val fileName: String) : ComputerVisionEffect()
    data object NavigateBack : ComputerVisionEffect()
}
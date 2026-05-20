package org.appdevforall.codeonthego.computervision.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.computervision.R
import org.appdevforall.codeonthego.computervision.data.repository.DrawableImportHelper
import org.appdevforall.codeonthego.computervision.data.repository.VisionRepository
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.usecase.GenerateXmlUC
import org.appdevforall.codeonthego.computervision.domain.usecase.ImportPlaceholderImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.PrepareImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.RemovePlaceholderImageUC
import org.appdevforall.codeonthego.computervision.domain.usecase.RunVisionUC
import org.appdevforall.codeonthego.computervision.ui.ComputerVisionEffect
import org.appdevforall.codeonthego.computervision.ui.ComputerVisionEvent
import org.appdevforall.codeonthego.computervision.ui.ComputerVisionUiState
import org.appdevforall.codeonthego.computervision.ui.CvOperation
import org.appdevforall.codeonthego.computervision.ui.SelectedImportedImage
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil
import org.appdevforall.codeonthego.computervision.utils.getSortedPlaceholders

class ComputerVisionViewModel(
    private val repository: VisionRepository,
    private val prepareImageUC: PrepareImageUC,
    private val runVisionUC: RunVisionUC,
    private val generateXmlUC: GenerateXmlUC,
    private val importPlaceholderImageUC: ImportPlaceholderImageUC,
    private val removePlaceholderImageUC: RemovePlaceholderImageUC,
    layoutFilePath: String?,
    layoutFileName: String?
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ComputerVisionUiState(
            layoutFilePath = layoutFilePath,
            layoutFileName = layoutFileName
        )
    )
    val uiState: StateFlow<ComputerVisionUiState> = _uiState.asStateFlow()

    private val _uiEffect = Channel<ComputerVisionEffect>()
    val uiEffect = _uiEffect.receiveAsFlow()

    init {
        initModel()
    }

    fun onEvent(event: ComputerVisionEvent) {
        when (event) {
            is ComputerVisionEvent.ImageSelected -> {
                CvAnalyticsUtil.trackImageSelected(fromCamera = false)
                loadImageFromUri(event.uri)
            }
            is ComputerVisionEvent.ImageCaptured -> handleCameraResult(event.uri, event.success)
            ComputerVisionEvent.RunDetection -> runDetection()
            ComputerVisionEvent.UpdateLayoutFile -> showUpdateConfirmation()
            ComputerVisionEvent.ConfirmUpdate -> performLayoutUpdate()
            ComputerVisionEvent.SaveToDownloads -> saveXmlToDownloads()
            ComputerVisionEvent.OpenImagePicker -> viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.OpenImagePicker) }
            ComputerVisionEvent.RequestCameraPermission -> viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.RequestCameraPermission) }
            is ComputerVisionEvent.UpdateGuides -> updateGuides(event.leftPct, event.rightPct)
            is ComputerVisionEvent.ImagePlaceholderTapped -> handleImagePlaceholderTap(event.imageX, event.imageY)
            is ComputerVisionEvent.PlaceholderImageSelected -> handlePlaceholderImageSelected(event.uri)
            is ComputerVisionEvent.RemovePlaceholderImage -> removePlaceholderImage(event.placeholderId)
        }
    }

    private fun initModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(currentOperation = CvOperation.InitializingModel) }
            repository.initModel()
                .onSuccess { _uiState.update { it.copy(isModelInitialized = true, currentOperation = CvOperation.Idle) } }
                .onFailure { exception ->
                    Log.e(TAG, "Model initialization failed", exception)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ShowError("Model initialization failed: ${exception.message}"))
                }
        }
    }

    fun onScreenStarted() {
        CvAnalyticsUtil.trackScreenOpened()
    }

    private fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch {
            prepareImageUC(uri).onSuccess { prepared ->
                _uiState.update {
                    it.copy(
                        currentBitmap = prepared.bitmap,
                        imageUri = uri,
                        detections = emptyList(),
                        visualizedBitmap = null,
                        leftGuidePct = prepared.leftPct,
                        rightGuidePct = prepared.rightPct,
                        parsedAnnotations = emptyMap(),
                        pendingImagePlaceholderId = null,
                        selectedImagesByPlaceholderId = emptyMap()
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "Error loading image", e)
                _uiEffect.send(ComputerVisionEffect.ShowError("Failed to load image: ${e.message}"))
            }
        }
    }

    private fun handleCameraResult(uri: Uri, success: Boolean) {
        if (success) {
            CvAnalyticsUtil.trackImageSelected(fromCamera = true)
            loadImageFromUri(uri)
        } else {
            viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_image_capture_cancelled)) }
        }
    }

    private fun runDetection() {
        val state = _uiState.value
        val bitmap = state.currentBitmap ?: run {
            viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_select_image_first)) }
            return
        }

        viewModelScope.launch {
            CvAnalyticsUtil.trackDetectionStarted()
            val startTime = System.currentTimeMillis()

            runVisionUC(bitmap, state.leftGuidePct, state.rightGuidePct) { operation ->
                _uiState.update { it.copy(currentOperation = operation) }
            }.onSuccess { result ->
                CvAnalyticsUtil.trackDetectionCompleted(true, result.detections.size, System.currentTimeMillis() - startTime)
                _uiState.update {
                    it.copy(
                        detections = result.detections,
                        parsedAnnotations = result.annotations,
                        currentOperation = CvOperation.Idle,
                        pendingImagePlaceholderId = null,
                        selectedImagesByPlaceholderId = emptyMap()
                    )
                }
            }.onFailure { exception ->
                Log.e(TAG, "Detection failed", exception)
                CvAnalyticsUtil.trackDetectionCompleted(false, 0, System.currentTimeMillis() - startTime)
                _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                _uiEffect.send(ComputerVisionEffect.ShowError("Detection failed: ${exception.message}"))
            }
        }
    }

    private fun showUpdateConfirmation() {
        val state = _uiState.value
        if (!state.hasDetections || state.currentBitmap == null) {
            viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_run_detection_first)) }
            return
        }
        viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.ShowConfirmDialog(state.layoutFileName ?: "layout.xml")) }
    }

    private fun performLayoutUpdate() {
        val state = _uiState.value
        if (!state.hasDetections || state.currentBitmap == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(currentOperation = CvOperation.GeneratingXml) }

            generateXml(state)
                .onSuccess { (layoutXml, stringsXml) ->
                    CvAnalyticsUtil.trackXmlGenerated(state.detections.size)
                    CvAnalyticsUtil.trackXmlExported(toDownloads = false)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ReturnXmlResult(layoutXml, stringsXml))
                }.onFailure { exception ->
                    Log.e(TAG, "XML generation failed", exception)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ShowError("XML generation failed: ${exception.message}"))
                }
        }
    }

    private fun saveXmlToDownloads() {
        val state = _uiState.value
        if (!state.hasDetections || state.currentBitmap == null) {
            viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_run_detection_first)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(currentOperation = CvOperation.GeneratingXml) }

            generateXml(state).onSuccess { (layoutXml, _) ->
                CvAnalyticsUtil.trackXmlGenerated(state.detections.size)
                CvAnalyticsUtil.trackXmlExported(toDownloads = true)
                _uiState.update { it.copy(currentOperation = CvOperation.SavingFile) }
                _uiState.update { it.copy(currentOperation = CvOperation.Idle) }

                _uiEffect.send(ComputerVisionEffect.FileSaved(layoutXml))
            }.onFailure { exception ->
                Log.e(TAG, "XML generation failed", exception)
                _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                _uiEffect.send(ComputerVisionEffect.ShowError("XML generation failed: ${exception.message}"))
            }
        }
    }

    private fun updateGuides(leftPct: Float, rightPct: Float) {
        val clampedLeft = leftPct.coerceIn(0f, 1f)
        val clampedRight = rightPct.coerceIn(0f, 1f)

        _uiState.update {
            it.copy(
                leftGuidePct = minOf(clampedLeft, clampedRight),
                rightGuidePct = maxOf(clampedLeft, clampedRight)
            )
        }
    }

    private fun generateXml(state: ComputerVisionUiState): Result<Pair<String, String>> {
        val bitmap = state.currentBitmap ?: return Result.failure(IllegalStateException("No bitmap available"))
        return generateXmlUC(
            detections = state.detections,
            annotations = state.parsedAnnotations,
            selectedImagesByPlaceholderId = state.selectedImagesByPlaceholderId.mapValues { it.value.drawableReference },
            sourceImageWidth = bitmap.width,
            sourceImageHeight = bitmap.height,
            targetDpWidth = TARGET_DP_WIDTH,
            targetDpHeight = TARGET_DP_HEIGHT
        )
    }

    private fun handleImagePlaceholderTap(imageX: Float, imageY: Float) {
        val placeholder = findImagePlaceholderAt(imageX, imageY) ?: return
        val placeholderId = resolvePlaceholderId(placeholder)

        _uiState.update { it.copy(pendingImagePlaceholderId = placeholderId) }
        viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.OpenPlaceholderImagePicker) }
    }

    private fun handlePlaceholderImageSelected(uri: Uri) {
        val state = _uiState.value
        val placeholderId = state.pendingImagePlaceholderId ?: return

        viewModelScope.launch {
            importPlaceholderImageUC(uri, state.layoutFilePath, placeholderId)
                .onSuccess { importedDrawable ->
                    _uiState.update { currentState ->
                        currentState.copy(
                            pendingImagePlaceholderId = null,
                            selectedImagesByPlaceholderId = currentState.selectedImagesByPlaceholderId +
                                (placeholderId to SelectedImportedImage(importedDrawable.resourceName, importedDrawable.drawableReference))
                        )
                    }
                    _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_placeholder_image_selected))
                }.onFailure { exception ->
                    _uiState.update { it.copy(pendingImagePlaceholderId = null) }
                    _uiEffect.send(ComputerVisionEffect.ShowError("Image import failed: ${exception.message}"))
                }
        }
    }

    private fun removePlaceholderImage(placeholderId: String) {
        val state = _uiState.value
        val importedImageInfo = state.selectedImagesByPlaceholderId[placeholderId] ?: return

        viewModelScope.launch {
            removePlaceholderImageUC(state.layoutFilePath, importedImageInfo.resourceName)
                .onSuccess {
                    _uiState.update { currentState ->
                        currentState.copy(selectedImagesByPlaceholderId = currentState.selectedImagesByPlaceholderId - placeholderId)
                    }
                    _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_image_removed))
                }.onFailure { exception ->
                    _uiEffect.send(ComputerVisionEffect.ShowError("Failed to clean up image file: ${exception.message}"))
                }
        }
    }

    private fun resolvePlaceholderId(detection: DetectionResult): String {
        val index = _uiState.value.detections.getSortedPlaceholders().indexOf(detection)
        return "ph_${index.coerceAtLeast(0)}"
    }

    fun isImagePlaceholderAt(imageX: Float, imageY: Float): Boolean {
        return findImagePlaceholderAt(imageX, imageY) != null
    }

    private fun findImagePlaceholderAt(imageX: Float, imageY: Float): DetectionResult? {
        return _uiState.value.detections
            .getSortedPlaceholders()
            .firstOrNull { it.boundingBox.contains(imageX, imageY) }
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }

    companion object {
        private const val TAG = "ComputerVisionViewModel"

        /** Standard Android phone viewport in dp used as the XML layout target size. */
        private const val TARGET_DP_WIDTH = 360
        private const val TARGET_DP_HEIGHT = 640
    }
}

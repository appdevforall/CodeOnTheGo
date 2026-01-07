package org.appdevforall.codeonthego.computervision.ui.viewmodel

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.appdevforall.codeonthego.computervision.data.repository.ComputerVisionRepository
import org.appdevforall.codeonthego.computervision.ui.ComputerVisionEffect
import org.appdevforall.codeonthego.computervision.ui.ComputerVisionEvent
import org.appdevforall.codeonthego.computervision.ui.ComputerVisionUiState
import org.appdevforall.codeonthego.computervision.ui.CvOperation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.computervision.R
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil

class ComputerVisionViewModel(
    private val repository: ComputerVisionRepository,
    private val contentResolver: ContentResolver,
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
        initializeModel()
        CvAnalyticsUtil.trackScreenOpened()
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
            ComputerVisionEvent.OpenImagePicker -> {
                viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.OpenImagePicker) }
            }

            ComputerVisionEvent.RequestCameraPermission -> {
                viewModelScope.launch { _uiEffect.send(ComputerVisionEffect.RequestCameraPermission) }
            }
        }
    }

    private fun initializeModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(currentOperation = CvOperation.InitializingModel) }

            repository.initializeModel()
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isModelInitialized = true,
                            currentOperation = CvOperation.Idle
                        )
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Model initialization failed", exception)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ShowError("Model initialization failed: ${exception.message}"))
                }
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch {
            try {

                val bitmap = uriToBitmap(uri)
                if (bitmap != null) {
                    _uiState.update {
                        it.copy(
                            currentBitmap = bitmap,
                            imageUri = uri,
                            detections = emptyList(),
                            visualizedBitmap = null
                        )
                    }
                } else {
                    _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_no_image_selected))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from URI", e)
                _uiEffect.send(ComputerVisionEffect.ShowError("Failed to load image: ${e.message}"))
            }
        }
    }

    private fun handleCameraResult(uri: Uri, success: Boolean) {
        if (success) {
            loadImageFromUri(uri)
        } else {
            viewModelScope.launch {
                _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_image_capture_cancelled))
            }
        }
    }

    private fun runDetection() {
        val bitmap = _uiState.value.currentBitmap
        if (bitmap == null) {
            viewModelScope.launch {
                _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_select_image_first))
            }
            return
        }

        viewModelScope.launch {
            CvAnalyticsUtil.trackDetectionStarted()
            val startTime = System.currentTimeMillis()
            _uiState.update { it.copy(currentOperation = CvOperation.RunningYolo) }

            val yoloResult = repository.runYoloInference(bitmap)
            if (yoloResult.isFailure) {
                val endTime = System.currentTimeMillis()
                val durationMs = endTime - startTime
                CvAnalyticsUtil.trackDetectionCompleted(success = false, detectionCount = 0, durationMs = durationMs)
                handleDetectionError(yoloResult.exceptionOrNull())
                return@launch
            }
            val yoloDetections = yoloResult.getOrThrow()

            _uiState.update { it.copy(currentOperation = CvOperation.RunningOcr) }

            val ocrBitmap = repository.preprocessBitmapForOcr(bitmap)
            val ocrResult = repository.runOcrRecognition(ocrBitmap)

            _uiState.update { it.copy(currentOperation = CvOperation.MergingDetections) }

            val textBlocks = ocrResult.getOrElse { emptyList() }
            val mergeResult = repository.mergeDetections(yoloDetections, textBlocks)

            mergeResult
                .onSuccess { mergedDetections ->
                    _uiState.update {
                        CvAnalyticsUtil.trackDetectionCompleted(
                            success = true,
                            detectionCount = mergedDetections.size,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                        it.copy(
                            detections = mergedDetections,
                            currentOperation = CvOperation.Idle
                        )
                    }
                    Log.d(TAG, "Detection complete. ${mergedDetections.size} objects detected.")
                }
                .onFailure { handleDetectionError(it) }
        }
    }

    private fun handleDetectionError(exception: Throwable?) {
        Log.e(TAG, "Detection failed", exception)
        viewModelScope.launch {
            _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
            _uiEffect.send(ComputerVisionEffect.ShowError("Detection failed: ${exception?.message}"))
        }
    }

    private fun showUpdateConfirmation() {
        val state = _uiState.value
        if (!state.hasDetections || state.currentBitmap == null) {
            viewModelScope.launch {
                _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_run_detection_first))
            }
            return
        }

        val fileName = state.layoutFileName ?: "layout.xml"
        viewModelScope.launch {
            _uiEffect.send(ComputerVisionEffect.ShowConfirmDialog(fileName))
        }
    }

    private fun performLayoutUpdate() {
        val state = _uiState.value
        if (!state.hasDetections || state.currentBitmap == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(currentOperation = CvOperation.GeneratingXml) }

            repository.generateXml(
                detections = state.detections,
                sourceImageWidth = state.currentBitmap.width,
                sourceImageHeight = state.currentBitmap.height
            )
                .onSuccess { xml ->
                    CvAnalyticsUtil.trackXmlGenerated(componentCount = state.detections.size)
                    CvAnalyticsUtil.trackXmlExported(toDownloads = false)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ReturnXmlResult(xml))
                }
                .onFailure { exception ->
                    Log.e(TAG, "XML generation failed", exception)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ShowError("XML generation failed: ${exception.message}"))
                }
        }
    }

    private fun saveXmlToDownloads() {
        val state = _uiState.value
        if (!state.hasDetections || state.currentBitmap == null) {
            viewModelScope.launch {
                _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_run_detection_first))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(currentOperation = CvOperation.GeneratingXml) }

            repository.generateXml(
                detections = state.detections,
                sourceImageWidth = state.currentBitmap.width,
                sourceImageHeight = state.currentBitmap.height
            )
                .onSuccess { xml ->
                    CvAnalyticsUtil.trackXmlGenerated(componentCount = state.detections.size)
                    CvAnalyticsUtil.trackXmlExported(toDownloads = true)
                    _uiState.update { it.copy(currentOperation = CvOperation.SavingFile) }
                    saveXmlFile(xml)
                }
                .onFailure { exception ->
                    Log.e(TAG, "XML generation failed", exception)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ShowError("XML generation failed: ${exception.message}"))
                }
        }
    }

    private suspend fun saveXmlFile(xmlString: String) {
        _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
        _uiEffect.send(ComputerVisionEffect.FileSaved(xmlString))
    }

    private fun uriToBitmap(selectedFileUri: Uri): Bitmap? {
        return try {
            contentResolver.openFileDescriptor(selectedFileUri, "r")?.use {
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap from URI", e)
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.releaseResources()
    }

    companion object {
        private const val TAG = "ComputerVisionViewModel"
    }
}
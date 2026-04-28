package org.appdevforall.codeonthego.computervision.ui.viewmodel

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import org.appdevforall.codeonthego.computervision.data.repository.ComputerVisionRepository
import org.appdevforall.codeonthego.computervision.domain.MarginAnnotationParser
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
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.computervision.R
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil
import org.appdevforall.codeonthego.computervision.utils.SmartBoundaryDetector

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

            is ComputerVisionEvent.UpdateGuides -> {
                _uiState.update {
                    it.copy(
                        leftGuidePct = event.leftPct,
                        rightGuidePct = event.rightPct
                    )
                }
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

    fun onScreenStarted() {
        CvAnalyticsUtil.trackScreenOpened()
    }

    private fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val bitmap = uriToBitmap(uri) ?: return@withContext null

                    val rotatedBitmap = handleImageRotation(uri, bitmap)
                    val (leftBoundPx, rightBoundPx) = SmartBoundaryDetector.detectSmartBoundaries(rotatedBitmap)

                    val widthFloat = rotatedBitmap.width.toFloat()
                    val leftPct = leftBoundPx / widthFloat
                    val rightPct = rightBoundPx / widthFloat

                    Triple(rotatedBitmap, leftPct, rightPct)
                }

                if (result == null) {
                    _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_no_image_selected))
                    return@launch
                }

                val (rotatedBitmap, leftPct, rightPct) = result

                _uiState.update {
                    it.copy(
                        currentBitmap = rotatedBitmap,
                        imageUri = uri,
                        detections = emptyList(),
                        visualizedBitmap = null,
                        leftGuidePct = leftPct,
                        rightGuidePct = rightPct,
                        parsedAnnotations = emptyMap() // Reset on new image
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from URI", e)
                _uiEffect.send(ComputerVisionEffect.ShowError("Failed to load image: ${e.message}"))
            }
        }
    }

    private fun handleCameraResult(uri: Uri, success: Boolean) {
        if (success) {
            CvAnalyticsUtil.trackImageSelected(fromCamera = true)
            loadImageFromUri(uri)
        } else {
            viewModelScope.launch {
                _uiEffect.send(ComputerVisionEffect.ShowToast(R.string.msg_image_capture_cancelled))
            }
        }
    }

    private fun handleImageRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                else -> return bitmap
            }
        }

        return try {
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            rotatedBitmap
        } catch (e: OutOfMemoryError) {
            bitmap
        }
    }

    private fun runDetection() {
        val state = _uiState.value
        val bitmap = state.currentBitmap
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
                CvAnalyticsUtil.trackDetectionCompleted(
                    success = false,
                    detectionCount = 0,
                    durationMs = endTime - startTime
                )
                handleDetectionError(yoloResult.exceptionOrNull())
                return@launch
            }
            val yoloDetections = yoloResult.getOrThrow()

            _uiState.update { it.copy(currentOperation = CvOperation.RunningOcr) }

            val regionOcrResult = repository.runRegionOcr(
                bitmap, yoloDetections, state.leftGuidePct, state.rightGuidePct
            )
            if (regionOcrResult.isFailure) {
                handleDetectionError(regionOcrResult.exceptionOrNull())
                return@launch
            }
            val ocrResult = regionOcrResult.getOrThrow()

            _uiState.update { it.copy(currentOperation = CvOperation.MergingDetections) }

            val mergeResult = repository.mergeDetections(
                enrichedComponents = ocrResult.enrichedDetections,
                remainingDetections = ocrResult.remainingDetections,
                fullImageTextBlocks = ocrResult.fullImageTextBlocks
            )

            mergeResult
                .onSuccess { mergedDetections ->
                    val leftBound = bitmap.width * state.leftGuidePct
                    val rightBound = bitmap.width * state.rightGuidePct
                    val canvasOnlyMerged = mergedDetections.filter { detection ->
                        detection.isYolo || detection.boundingBox.centerX() in leftBound..rightBound
                    }
                    val allDetections = canvasOnlyMerged + ocrResult.marginDetections

                    val (canvasDetections, annotationMap) = MarginAnnotationParser.parse(
                        detections = allDetections,
                        imageWidth = bitmap.width,
                        leftGuidePct = state.leftGuidePct,
                        rightGuidePct = state.rightGuidePct
                    )

                    CvAnalyticsUtil.trackDetectionCompleted(
                        success = true,
                        detectionCount = canvasDetections.size,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                    _uiState.update {
                        it.copy(
                            detections = canvasDetections,
                            parsedAnnotations = annotationMap,
                            currentOperation = CvOperation.Idle
                        )
                    }
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

            generateXml(state)
                .onSuccess { (layoutXml, stringsXml) ->
                    CvAnalyticsUtil.trackXmlGenerated(componentCount = state.detections.size)
                    CvAnalyticsUtil.trackXmlExported(toDownloads = false)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ReturnXmlResult(layoutXml, stringsXml))
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

            generateXml(state)
                .onSuccess { (layoutXml, stringsXml) ->
                    val combined = if(stringsXml.isNotBlank()) "$layoutXml\n\n\n" else layoutXml
                    CvAnalyticsUtil.trackXmlGenerated(componentCount = state.detections.size)
                    CvAnalyticsUtil.trackXmlExported(toDownloads = true)
                    _uiState.update { it.copy(currentOperation = CvOperation.SavingFile) }
                    saveXmlFile(combined)
                }
                .onFailure { exception ->
                    Log.e(TAG, "XML generation failed", exception)
                    _uiState.update { it.copy(currentOperation = CvOperation.Idle) }
                    _uiEffect.send(ComputerVisionEffect.ShowError("XML generation failed: ${exception.message}"))
                }
        }
    }

    private suspend fun generateXml(state: ComputerVisionUiState): Result<Pair<String, String>> {
        val bitmap = state.currentBitmap ?: return Result.failure(IllegalStateException("No bitmap available"))
        return repository.generateXml(
            detections = state.detections,
            annotations = state.parsedAnnotations,
            sourceImageWidth = bitmap.width,
            sourceImageHeight = bitmap.height,
            targetDpWidth = TARGET_DP_WIDTH,
            targetDpHeight = TARGET_DP_HEIGHT
        )
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

        /** Standard Android phone viewport in dp used as the XML layout target size. */
        private const val TARGET_DP_WIDTH = 360
        private const val TARGET_DP_HEIGHT = 640
    }
}
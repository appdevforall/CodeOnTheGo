package org.appdevforall.codeonthego.computervision.data.source

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class YoloModelSource {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    companion object {
        private const val MODEL_INPUT_WIDTH = 640
        private const val MODEL_INPUT_HEIGHT = 640
        private const val CONFIDENCE_THRESHOLD = 0.3f
        private const val NMS_THRESHOLD = 0.45f
    }

    @Throws(IOException::class)
    fun initialize(assetManager: AssetManager, modelPath: String = "best_float32.tflite", labelsPath: String = "labels.txt") {
        interpreter = Interpreter(loadModelFile(assetManager, modelPath))
        labels = assetManager.open(labelsPath).bufferedReader()
            .useLines { lines -> lines.map { it.trim() }.toList() }
    }

    fun isInitialized(): Boolean = interpreter != null && labels.isNotEmpty()

    fun runInference(bitmap: Bitmap): List<DetectionResult> {
        val interp = interpreter ?: throw IllegalStateException("Model not initialized")

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 255.0f))
            .add(CastOp(DataType.FLOAT32))
            .build()

        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))
        val outputShape = interp.getOutputTensor(0).shape()
        val outputBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)

        interp.run(tensorImage.buffer, outputBuffer.buffer.rewind())

        return processYoloOutput(outputBuffer, bitmap.width, bitmap.height)
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }

    private fun processYoloOutput(buffer: TensorBuffer, imageWidth: Int, imageHeight: Int): List<DetectionResult> {
        val shape = buffer.shape
        val numProperties = shape[1]
        val numPredictions = shape[2]
        val numClasses = numProperties - 4
        val floatArray = buffer.floatArray

        val transposedArray = FloatArray(shape[0] * numPredictions * numProperties)
        for (i in 0 until numPredictions) {
            for (j in 0 until numProperties) {
                transposedArray[i * numProperties + j] = floatArray[j * numPredictions + i]
            }
        }

        val allDetections = mutableListOf<DetectionResult>()
        for (i in 0 until numPredictions) {
            val offset = i * numProperties
            var maxClassScore = 0f
            var classId = -1
            for (j in 0 until numClasses) {
                val classScore = transposedArray[offset + 4 + j]
                if (classScore > maxClassScore) {
                    maxClassScore = classScore
                    classId = j
                }
            }
            if (maxClassScore > CONFIDENCE_THRESHOLD) {
                val x = transposedArray[offset + 0]
                val y = transposedArray[offset + 1]
                val w = transposedArray[offset + 2]
                val h = transposedArray[offset + 3]

                val left = (x - w / 2) * imageWidth
                val top = (y - h / 2) * imageHeight
                val right = (x + w / 2) * imageWidth
                val bottom = (y + h / 2) * imageHeight

                val label = labels.getOrElse(classId) { "Unknown" }
                allDetections.add(DetectionResult(RectF(left, top, right, bottom), label, maxClassScore))
            }
        }

        return applyNms(allDetections)
    }

    private fun applyNms(detections: List<DetectionResult>): List<DetectionResult> {
        val finalDetections = mutableListOf<DetectionResult>()
        val groupedByLabel = detections.groupBy { it.label }

        for ((_, group) in groupedByLabel) {
            val sortedDetections = group.sortedByDescending { it.score }
            val remaining = sortedDetections.toMutableList()

            while (remaining.isNotEmpty()) {
                val bestDetection = remaining.first()
                finalDetections.add(bestDetection)
                remaining.remove(bestDetection)

                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val detection = iterator.next()
                    if (calculateIoU(bestDetection.boundingBox, detection.boundingBox) > NMS_THRESHOLD) {
                        iterator.remove()
                    }
                }
            }
        }

        return finalDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val xA = maxOf(box1.left, box2.left)
        val yA = maxOf(box1.top, box2.top)
        val xB = minOf(box1.right, box2.right)
        val yB = minOf(box1.bottom, box2.bottom)

        val intersectionArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea == 0f) 0f else intersectionArea / unionArea
    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, modelPath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        return FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            inputStream.channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
        }
    }
}
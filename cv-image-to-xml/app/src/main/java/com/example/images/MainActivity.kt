package com.example.images

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    // This data class is used by both MainActivity and DetectionMerger.
    // It must be defined here or in its own file.
    data class DetectionResult(
        val boundingBox: RectF,
        val label: String,
        val score: Float,
        var text: String = "",
        val isYolo: Boolean = true
    )

    private lateinit var frame: ImageView
    private lateinit var interpreter: Interpreter
    private lateinit var labels: List<String>
    private var imageUri: Uri? = null
    private var currentBitmap: Bitmap? = null
    private var lastDetections: List<DetectionResult> = emptyList()

    private val textRecognizer by lazy { com.google.mlkit.vision.text.TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val boundingBoxPaint by lazy { Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5.0f; alpha = 200 } }
    private val textRecognitionBoxPaint by lazy { Paint().apply { color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 3.0f; alpha = 200 } }
    private val textPaint by lazy { Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; textSize = 40.0f; setShadowLayer(5.0f, 0f, 0f, Color.BLACK) } }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imageUri = it
            uriToBitmap(it)?.let { bitmap ->
                currentBitmap = bitmap
                frame.setImageBitmap(currentBitmap)
                lastDetections = emptyList()
            }
        } ?: Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
        if (isSuccess) {
            imageUri?.let { uri ->
                uriToBitmap(uri)?.let { bitmap ->
                    currentBitmap = bitmap
                    frame.setImageBitmap(currentBitmap)
                    lastDetections = emptyList()
                }
            }
        } else {
            Toast.makeText(this, "Image capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) openCamera() else Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frame = findViewById(R.id.imageView)
        val detectButton: Button = findViewById(R.id.detectButton)
        val shareButton: Button = findViewById(R.id.shareButton)
        val saveButton: Button = findViewById(R.id.saveButton)

        initializeModelAndLabels()

        frame.setOnClickListener { pickImageLauncher.launch("image/*") }
        frame.setOnLongClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            true
        }
        detectButton.setOnClickListener {
            currentBitmap?.let { runObjectDetection(it) } ?: Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show()
        }
        shareButton.setOnClickListener { generateAndProcessXml(share = true) }
        saveButton.setOnClickListener { generateAndProcessXml(save = true) }
    }

    /**
     * This function now delegates all complex merging logic to the new, robust DetectionMerger class.
     */
    private fun runObjectDetection(bitmap: Bitmap) {
        val yoloDetections = runYoloInference(bitmap)
        val ocrBitmap = preprocessBitmapForOcr(bitmap)
        val imageForText = InputImage.fromBitmap(ocrBitmap, 0)

        textRecognizer.process(imageForText)
            .addOnSuccessListener { visionText ->
                // *** USE THE NEW, SAFE MERGER CLASS ***
                val merger = DetectionMerger(yoloDetections, visionText.textBlocks)
                val mergedDetections = merger.merge()

                // Post-process the results to clean the text
                mergedDetections.forEach { detection ->
                    if (detection.text.isNotEmpty()) {
                        // This updates the 'text' property of the DetectionResult in place
                        detection.text = cleanText(detection.text)
                    }
                }

                this.lastDetections = mergedDetections // Now contains cleaned text
                visualizeResults(bitmap, mergedDetections) // Will display cleaned text
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
                this.lastDetections = yoloDetections
                visualizeResults(bitmap, yoloDetections)
            }
    }

    // --- The rest of the file remains unchanged. ---
    // All boilerplate functions for YOLO, file saving, etc., are the same.

    override fun onDestroy() {
        super.onDestroy()
        if (::interpreter.isInitialized) {
            interpreter.close()
        }
    }

    private fun initializeModelAndLabels() {
        try {
            interpreter = Interpreter(loadModelFile(assets, "best_float32.tflite"))
            labels = assets.open("labels.txt").bufferedReader().useLines { lines -> lines.map { it.trim() }.toList() }
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing TFLite model or labels", e)
            Toast.makeText(this, "Model or labels could not be loaded", Toast.LENGTH_LONG).show()
        }
    }

    private fun preprocessBitmapForOcr(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val grayPixels = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            grayPixels[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }
        val integralImage = LongArray(width * height)
        for (y in 0 until height) {
            var rowSum: Long = 0
            for (x in 0 until width) {
                rowSum += grayPixels[y * width + x]
                integralImage[y * width + x] = if (y == 0) rowSum else integralImage[(y - 1) * width + x] + rowSum
            }
        }
        val thresholdPixels = IntArray(width * height)
        val s = width / 16
        val t = 0.15f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val x1 = maxOf(0, x - s / 2)
                val x2 = minOf(width - 1, x + s / 2)
                val y1 = maxOf(0, y - s / 2)
                val y2 = minOf(height - 1, y + s / 2)
                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integralImage[y2 * width + x2] -
                        (if (x1 > 0) integralImage[y2 * width + x1 - 1] else 0) -
                        (if (y1 > 0) integralImage[(y1 - 1) * width + x2] else 0) +
                        (if (x1 > 0 && y1 > 0) integralImage[(y1 - 1) * width + x1 - 1] else 0)
                if (grayPixels[index] * count < sum * (1.0f - t)) {
                    thresholdPixels[index] = Color.BLACK
                } else {
                    thresholdPixels[index] = Color.WHITE
                }
            }
        }
        return Bitmap.createBitmap(thresholdPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun visualizeResults(bitmap: Bitmap, detections: List<DetectionResult>) {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        for (result in detections) {
            val paint = if (result.isYolo) boundingBoxPaint else textRecognitionBoxPaint
            canvas.drawRect(result.boundingBox, paint)
            val label = result.label.take(15)
            val text = if (result.text.isNotEmpty()) "${label}: ${result.text}" else label
            canvas.drawText(text, result.boundingBox.left, result.boundingBox.top - 5, textPaint)
        }
        frame.setImageBitmap(mutableBitmap)
        Log.d(TAG, "Inference complete. Displaying ${detections.size} final objects.")
    }

    private fun generateAndProcessXml(share: Boolean = false, save: Boolean = false) {
        if (lastDetections.isEmpty() || currentBitmap == null) {
            Toast.makeText(this, "Please run detection on an image first.", Toast.LENGTH_SHORT).show()
            return
        }
        val generatedXml = YoloToXmlConverter.generateXmlLayout(
            detections = lastDetections,
            sourceImageWidth = currentBitmap!!.width,
            sourceImageHeight = currentBitmap!!.height,
            targetDpWidth = 360,
            targetDpHeight = 640
        )
        Log.d("XmlOutput", generatedXml)
        if (share) shareXml(generatedXml)
        if (save) saveXmlToFile(generatedXml)
    }

    private fun saveXmlToFile(xmlString: String) {
        val fileName = "testing_result.xml"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/xml")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: throw IOException("Failed to create new MediaStore record.")
                resolver.openOutputStream(uri).use { outputStream -> outputStream?.write(xmlString.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outputStream -> outputStream.write(xmlString.toByteArray()) }
            }
            Toast.makeText(this, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save XML file", e)
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareXml(xmlString: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, xmlString)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share XML Layout"))
    }

    private fun runYoloInference(bitmap: Bitmap): List<DetectionResult> {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(MODEL_INPUT_HEIGHT, MODEL_INPUT_WIDTH, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 255.0f))
            .add(CastOp(DataType.FLOAT32))
            .build()
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))
        val outputShape = interpreter.getOutputTensor(0).shape()
        val outputBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)
        interpreter.run(tensorImage.buffer, outputBuffer.buffer.rewind())
        return processYoloOutput(outputBuffer, bitmap.width, bitmap.height)
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
                    if (calculateIoU(bestDetection.boundingBox, detection.boundingBox) > 0.45f) {
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

    /**
     * Cleans a string to keep only alphanumeric characters and spaces.
     * Replaces newlines with spaces first.
     */
    private fun cleanText(text: String): String {
        // This regex matches anything that is NOT a letter (a-z, A-Z),
        // a number (0-9), or a space.
        val nonAlphanumericRegex = Regex("[^a-zA-Z0-9 ]")

        // First, replace any newlines with a space, then clean everything else
        return text.replace("\n", " ")
            .replace(nonAlphanumericRegex, "")
            .trim() // Remove leading/trailing spaces
    }

    private fun openCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "New Picture")
            put(MediaStore.Images.Media.DESCRIPTION, "From the Camera")
        }
        imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        imageUri?.let { takePictureLauncher.launch(it) }
    }

    private fun uriToBitmap(selectedFileUri: Uri): Bitmap? {
        return try {
            contentResolver.openFileDescriptor(selectedFileUri, "r")?.use {
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error decoding bitmap from URI", e)
            null
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, modelPath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        return FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            inputStream.channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MODEL_INPUT_WIDTH = 640
        private const val MODEL_INPUT_HEIGHT = 640
        private const val CONFIDENCE_THRESHOLD = 0.3f
        private const val NMS_THRESHOLD = 0.45f
    }
}
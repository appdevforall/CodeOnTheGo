package org.appdevforall.codeonthego.computervision.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.FeedbackButtonManager
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.computervision.R
import org.appdevforall.codeonthego.computervision.databinding.ActivityComputerVisionBinding
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.ui.viewmodel.ComputerVisionViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ComputerVisionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComputerVisionBinding
    private var feedbackButtonManager: FeedbackButtonManager? = null
    private val viewModel: ComputerVisionViewModel by viewModel {
        parametersOf(
            intent.getStringExtra(EXTRA_LAYOUT_FILE_PATH),
            intent.getStringExtra(EXTRA_LAYOUT_FILE_NAME)
        )
    }

    private val boundingBoxPaint by lazy {
        Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 5.0f
            alpha = 200
        }
    }

    private val textRecognitionBoxPaint by lazy {
        Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = 3.0f
            alpha = 200
        }
    }

    private val textPaint by lazy {
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textSize = 40.0f
            setShadowLayer(5.0f, 0f, 0f, Color.BLACK)
        }
    }

    private var currentCameraUri: android.net.Uri? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onEvent(ComputerVisionEvent.ImageSelected(it)) }
            ?: Toast.makeText(this, R.string.msg_no_image_selected, Toast.LENGTH_SHORT).show()
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        currentCameraUri?.let { uri ->
            viewModel.onEvent(ComputerVisionEvent.ImageCaptured(uri, success))
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, R.string.msg_camera_permission_required, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComputerVisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupClickListeners()
        observeViewModel()
        setupFeedbackButton()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupClickListeners() {
        binding.imageView.setOnClickListener {
            viewModel.onEvent(ComputerVisionEvent.OpenImagePicker)
        }
        binding.detectButton.setOnClickListener {
            viewModel.onEvent(ComputerVisionEvent.RunDetection)
        }
        binding.updateButton.setOnClickListener {
            viewModel.onEvent(ComputerVisionEvent.UpdateLayoutFile)
        }
        binding.saveButton.setOnClickListener {
            viewModel.onEvent(ComputerVisionEvent.SaveToDownloads)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.onScreenStarted()
                viewModel.uiState.collect { state -> updateUi(state) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEffect.collect { effect -> handleEffect(effect) }
            }
        }
    }
    private fun setupFeedbackButton(){
        feedbackButtonManager =
            FeedbackButtonManager(
                activity = this,
                feedbackFab = binding.fabFeedback,
            )
        feedbackButtonManager?.setupDraggableFab()
    }
    private fun updateUi(state: ComputerVisionUiState) {
        val displayBitmap = if (state.hasDetections && state.currentBitmap != null) {
            visualizeDetections(state.currentBitmap, state.detections)
        } else {
            state.currentBitmap
        }
        binding.imageView.setImageBitmap(displayBitmap)

        val isIdle = state.currentOperation == CvOperation.Idle
        binding.detectButton.isEnabled = state.canRunDetection
        binding.updateButton.isEnabled = state.canGenerateXml
        binding.saveButton.isEnabled = state.canGenerateXml
    }

    private fun handleEffect(effect: ComputerVisionEffect) {
        when (effect) {
            ComputerVisionEffect.OpenImagePicker -> pickImageLauncher.launch("image/*")
            ComputerVisionEffect.RequestCameraPermission ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            is ComputerVisionEffect.LaunchCamera -> {
                currentCameraUri = effect.outputUri
                takePictureLauncher.launch(effect.outputUri)
            }
            is ComputerVisionEffect.ShowToast ->
                Toast.makeText(this, effect.messageResId, Toast.LENGTH_SHORT).show()
            is ComputerVisionEffect.ShowError ->
                Toast.makeText(this, effect.message, Toast.LENGTH_LONG).show()
            is ComputerVisionEffect.ShowConfirmDialog ->
                showUpdateConfirmationDialog(effect.fileName)
            is ComputerVisionEffect.ReturnXmlResult -> returnXmlResult(effect.xml)
            is ComputerVisionEffect.FileSaved -> saveXmlToFile(effect.fileName)
            ComputerVisionEffect.NavigateBack -> finish()
        }
    }

    private fun visualizeDetections(bitmap: Bitmap, detections: List<DetectionResult>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        for (result in detections) {
            val paint = if (result.isYolo) boundingBoxPaint else textRecognitionBoxPaint
            canvas.drawRect(result.boundingBox, paint)
            val label = result.label.take(15)
            val text = if (result.text.isNotEmpty()) "${label}: ${result.text}" else label
            canvas.drawText(text, result.boundingBox.left, result.boundingBox.top - 5, textPaint)
        }
        Log.d(TAG, "Visualizing ${detections.size} detections")
        return mutableBitmap
    }

    private fun showUpdateConfirmationDialog(fileName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_update_layout)
            .setMessage(getString(R.string.msg_overwrite_layout, fileName))
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { dialog, _ ->
                dialog.dismiss()
                viewModel.onEvent(ComputerVisionEvent.ConfirmUpdate)
            }
            .setCancelable(false)
            .show()
    }

    private fun returnXmlResult(xml: String) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(RESULT_GENERATED_XML, xml)
        })
        finish()
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
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("Failed to create new MediaStore record.")
                resolver.openOutputStream(uri).use { outputStream ->
                    outputStream?.write(xmlString.toByteArray())
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    outputStream.write(xmlString.toByteArray())
                }
            }
            Toast.makeText(this, getString(R.string.msg_saved_to_downloads, fileName), Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save XML file", e)
            Toast.makeText(this, getString(R.string.msg_error_saving_file, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        feedbackButtonManager?.loadFabPosition()
    }
    private fun launchCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, getString(R.string.camera_picture_title))
            put(MediaStore.Images.Media.DESCRIPTION, getString(R.string.camera_picture_description))
        }
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            currentCameraUri = uri
            takePictureLauncher.launch(uri)
        }
    }

    companion object {
        private const val TAG = "ComputerVisionActivity"
        const val EXTRA_LAYOUT_FILE_PATH = "com.example.images.LAYOUT_FILE_PATH"
        const val EXTRA_LAYOUT_FILE_NAME = "com.example.images.LAYOUT_FILE_NAME"
        const val RESULT_GENERATED_XML = "ide.uidesigner.generatedXml"
    }
}

package org.appdevforall.codeonthego.computervision.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
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
import org.appdevforall.codeonthego.computervision.ui.viewmodel.ComputerVisionViewModel
import org.appdevforall.codeonthego.computervision.utils.DetectionVisualizer
import org.appdevforall.codeonthego.computervision.utils.XmlFileManager
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class ComputerVisionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComputerVisionBinding
    private var feedbackButtonManager: FeedbackButtonManager? = null

    private val detectionVisualizer by lazy { DetectionVisualizer(this) }
    private val xmlFileManager by lazy { XmlFileManager(this) }

    private val viewModel: ComputerVisionViewModel by viewModel {
        parametersOf(
            intent.getStringExtra(EXTRA_LAYOUT_FILE_PATH),
            intent.getStringExtra(EXTRA_LAYOUT_FILE_NAME)
        )
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

    private val pickPlaceholderImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onEvent(ComputerVisionEvent.PlaceholderImageSelected(it)) }
            ?: Toast.makeText(this, R.string.msg_no_image_selected, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComputerVisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupClickListeners()
        observeViewModel()
        setupFeedbackButton()
        setupGuidelines()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupClickListeners() {
        with(binding) {
            imageView.setOnClickListener { viewModel.onEvent(ComputerVisionEvent.OpenImagePicker) }
            detectButton.setOnClickListener { viewModel.onEvent(ComputerVisionEvent.RunDetection) }
            updateButton.setOnClickListener { viewModel.onEvent(ComputerVisionEvent.UpdateLayoutFile) }
            saveButton.setOnClickListener { viewModel.onEvent(ComputerVisionEvent.SaveToDownloads) }
            imageView.onImageTapListener = ::handleImageTap
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.onScreenStarted()
                launch { viewModel.uiState.collect { updateUi(it) } }
                launch { viewModel.uiEffect.collect { handleEffect(it) } }
            }
        }
    }

    private fun setupFeedbackButton(){
        feedbackButtonManager = FeedbackButtonManager(activity = this, feedbackFab = binding.fabFeedback)
        feedbackButtonManager?.setupDraggableFab()
    }

    private fun setupGuidelines() {
        binding.imageView.onMatrixChangeListener = { matrix ->
            binding.guidelinesView.updateMatrix(matrix)
        }
        binding.guidelinesView.onGuidelinesChanged = { left, right ->
            viewModel.onEvent(ComputerVisionEvent.UpdateGuides(left, right))
        }
    }

    private fun updateUi(state: ComputerVisionUiState) {
        val displayBitmap = if (state.hasDetections && state.currentBitmap != null) {
            detectionVisualizer.visualize(
                bitmap = state.currentBitmap,
                detections = state.detections,
                selectedPlaceholderIds = state.selectedImagesByPlaceholderId.keys
            )
        } else {
            detectionVisualizer.clearCache()
            state.currentBitmap
        }

        binding.imageView.setImageBitmap(displayBitmap)
        state.currentBitmap?.let {
            binding.guidelinesView.setImageDimensions(it.width, it.height)
        }
        binding.guidelinesView.updateGuidelines(state.leftGuidePct, state.rightGuidePct)

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
            is ComputerVisionEffect.ReturnXmlResult -> returnXmlResult(effect.layoutXml, effect.stringsXml)
            ComputerVisionEffect.NavigateBack -> finish()
            ComputerVisionEffect.OpenPlaceholderImagePicker ->
                pickPlaceholderImageLauncher.launch("image/*")
            is ComputerVisionEffect.FileSaved -> saveXmlFile(effect.fileName)
        }
    }

    /**
     * Handles tap events on the image view, determining whether the user tapped
     * a delete action or a general placeholder, and routes the event to the ViewModel.
     *
     * @param imageX The X coordinate of the tap on the original image.
     * @param imageY The Y coordinate of the tap on the original image.
     * @return True if the tap was handled, false otherwise.
     */
    private fun handleImageTap(imageX: Float, imageY: Float): Boolean {
        val tappedDeleteId = detectionVisualizer.getTappedDeleteIconId(imageX, imageY)
        if (tappedDeleteId != null) {
            viewModel.onEvent(ComputerVisionEvent.RemovePlaceholderImage(tappedDeleteId))
            return true
        }

        if (!viewModel.isImagePlaceholderAt(imageX, imageY)) return false

        viewModel.onEvent(ComputerVisionEvent.ImagePlaceholderTapped(imageX, imageY))
        return true
    }

    private fun saveXmlFile(xmlString: String) {
        val result = xmlFileManager.saveXmlToDownloads(xmlString)
        result.onSuccess { fileName ->
            Toast.makeText(this, getString(R.string.msg_saved_to_downloads, fileName), Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            Toast.makeText(this, getString(R.string.msg_error_saving_file, error.message), Toast.LENGTH_SHORT).show()
        }
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

    private fun returnXmlResult(layoutXml: String, stringsXml: String) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(RESULT_GENERATED_XML, layoutXml)
            putExtra(RESULT_GENERATED_STRINGS, stringsXml)
            putExtra(EXTRA_LAYOUT_FILE_PATH, intent.getStringExtra(EXTRA_LAYOUT_FILE_PATH))
        })
        finish()
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

    override fun onResume() {
        super.onResume()
        feedbackButtonManager?.loadFabPosition()
    }

    companion object {
        const val EXTRA_LAYOUT_FILE_PATH = "com.example.images.LAYOUT_FILE_PATH"
        const val EXTRA_LAYOUT_FILE_NAME = "com.example.images.LAYOUT_FILE_NAME"
        const val RESULT_GENERATED_XML = "ide.uidesigner.generatedXml"
        const val RESULT_GENERATED_STRINGS = "ide.uidesigner.generatedStrings"
    }
}

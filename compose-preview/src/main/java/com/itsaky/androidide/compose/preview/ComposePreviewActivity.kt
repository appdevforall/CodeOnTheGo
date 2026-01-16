package com.itsaky.androidide.compose.preview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.compose.preview.compiler.CompileDiagnostic
import com.itsaky.androidide.compose.preview.databinding.ActivityComposePreviewBinding
import com.itsaky.androidide.compose.preview.runtime.ComposeClassLoader
import com.itsaky.androidide.compose.preview.runtime.ComposableRenderer
import com.itsaky.androidide.compose.preview.ui.BoundedComposeView
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R as ResourcesR
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ComposePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposePreviewBinding

    private val viewModel: ComposePreviewViewModel by viewModels()

    private var classLoader: ComposeClassLoader? = null
    private var singleRenderer: ComposableRenderer? = null
    private val multiRenderers = mutableMapOf<String, ComposableRenderer>()

    private var toggleMenuItem: android.view.MenuItem? = null
    private var selectorAdapter: ArrayAdapter<String>? = null

    private val sourceCode: String by lazy {
        intent.getStringExtra(EXTRA_SOURCE_CODE) ?: ""
    }

    private val filePath: String by lazy {
        intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClassLoader()
        setupToolbar()
        setupPreviewSelector()
        setupSinglePreview()
        setupBuildButton()
        observeState()

        viewModel.initialize(this, filePath)

        if (sourceCode.isNotBlank()) {
            viewModel.onSourceChanged(sourceCode)
        }
    }

    private fun setupClassLoader() {
        classLoader = ComposeClassLoader(this)
    }

    private fun setupToolbar() {
        binding.toolbar.title = filePath.substringAfterLast('/').ifEmpty {
            getString(ResourcesR.string.title_compose_preview)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        toggleMenuItem = binding.toolbar.menu.findItem(R.id.action_toggle_mode)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_toggle_mode -> {
                    viewModel.toggleDisplayMode()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPreviewSelector() {
        selectorAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf()
        )
        selectorAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.previewSelector.adapter = selectorAdapter

        binding.previewSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = selectorAdapter?.getItem(position) ?: return
                viewModel.selectPreview(selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSinglePreview() {
        binding.singlePreviewView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        singleRenderer = classLoader?.let { ComposableRenderer(binding.singlePreviewView, it) }
    }

    private fun setupBuildButton() {
        binding.buildProjectButton.setOnClickListener {
            triggerBuild()
        }
        binding.errorBuildButton.setOnClickListener {
            triggerBuildFromError()
        }
    }

    private fun triggerBuild() {
        val state = viewModel.previewState.value
        if (state !is PreviewState.NeedsBuild) return

        executeBuild(state.modulePath, state.variantName)
    }

    private fun triggerBuildFromError() {
        val modulePath = viewModel.getModulePath()
        val variantName = viewModel.getVariantName()
        executeBuild(modulePath, variantName)
    }

    private fun executeBuild(modulePath: String, variantName: String) {
        val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
        if (buildService == null) {
            LOG.error("BuildService not available")
            return
        }

        if (buildService.isBuildInProgress) {
            LOG.warn("Build already in progress")
            return
        }

        viewModel.setBuildingState()

        val capitalizedVariant = variantName.replaceFirstChar { it.uppercaseChar() }
        val task = if (modulePath.isNotEmpty()) {
            "$modulePath:assemble$capitalizedVariant"
        } else {
            "assemble$capitalizedVariant"
        }
        LOG.info("Running build task: {}", task)

        buildService.executeTasks(task).whenComplete { result, error ->
            runOnUiThread {
                if (error != null || !result.isSuccessful) {
                    LOG.error("Build failed", error)
                    viewModel.setBuildFailed()
                } else {
                    LOG.info("Build completed, refreshing preview")
                    viewModel.refreshAfterBuild()
                }
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.previewState.collect { state ->
                    handlePreviewState(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.displayMode.collect { mode ->
                    updateDisplayMode(mode)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.availablePreviews.collect { previews ->
                    updatePreviewSelector(previews)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedPreview
                    .combine(viewModel.previewState) { selected, state -> Pair(selected, state) }
                    .collect { (selected, state) ->
                        if (state is PreviewState.Ready &&
                            viewModel.displayMode.value == DisplayMode.SINGLE &&
                            selected != null) {
                            renderSinglePreview(state, selected)
                        }
                    }
            }
        }
    }

    private fun handlePreviewState(state: PreviewState) {
        binding.loadingOverlay.isVisible = state is PreviewState.Initializing ||
            state is PreviewState.Compiling ||
            state is PreviewState.Idle ||
            state is PreviewState.Building
        binding.errorContainer.isVisible = state is PreviewState.Error
        binding.emptyContainer.isVisible = state is PreviewState.Empty
        binding.needsBuildContainer.isVisible = state is PreviewState.NeedsBuild

        val isReady = state is PreviewState.Ready
        val isAllMode = viewModel.displayMode.value == DisplayMode.ALL

        binding.previewScrollView.isVisible = isReady && isAllMode
        binding.singlePreviewView.isVisible = isReady && !isAllMode

        when (state) {
            is PreviewState.Idle -> {
                binding.statusText.text = "Rendering..."
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Initializing -> {
                binding.statusText.text = "Initializing..."
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Compiling -> {
                binding.statusText.text = "Compiling..."
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Building -> {
                binding.statusText.text = "Building project..."
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.NeedsBuild -> {
                LOG.debug("Build required for multi-file preview support")
            }
            is PreviewState.Empty -> {
                LOG.debug("No preview composables found")
            }
            is PreviewState.Ready -> {
                LOG.info("Runtime DEX from state: {}, project DEX files: {}",
                    state.runtimeDex?.absolutePath ?: "null", state.projectDexFiles.size)
                classLoader?.setProjectDexFiles(state.projectDexFiles)
                classLoader?.setRuntimeDex(state.runtimeDex)
                if (viewModel.displayMode.value == DisplayMode.ALL) {
                    renderAllPreviews(state)
                } else {
                    val selected = viewModel.selectedPreview.value
                    if (selected != null) {
                        renderSinglePreview(state, selected)
                    }
                }
            }
            is PreviewState.Error -> {
                binding.errorMessage.text = state.message
                val details = if (state.diagnostics.isNotEmpty()) {
                    state.diagnostics.joinToString("\n\n") { diagnostic ->
                        buildString {
                            if (diagnostic.file != null || diagnostic.line != null) {
                                diagnostic.file?.let { append(it.substringAfterLast('/')) }
                                diagnostic.line?.let { append(":$it") }
                                diagnostic.column?.let { append(":$it") }
                                append("\n")
                            }
                            append("[${diagnostic.severity}] ${diagnostic.message}")
                        }
                    }
                } else {
                    state.message
                }
                binding.errorDetails.text = details
                binding.errorDetails.isVisible = true
                binding.errorBuildButton.isVisible = viewModel.canTriggerBuild()

                LOG.error("Preview error: {}", state.message)
                LOG.error("Diagnostics: {}", details)
            }
        }
    }

    private fun updateDisplayMode(mode: DisplayMode) {
        val isAllMode = mode == DisplayMode.ALL

        toggleMenuItem?.setIcon(
            if (isAllMode) R.drawable.ic_view_single else R.drawable.ic_view_grid
        )

        binding.previewSelector.isVisible = !isAllMode && viewModel.availablePreviews.value.size > 1

        val state = viewModel.previewState.value
        if (state is PreviewState.Ready) {
            binding.previewScrollView.isVisible = isAllMode
            binding.singlePreviewView.isVisible = !isAllMode

            if (isAllMode) {
                renderAllPreviews(state)
            } else {
                val selected = viewModel.selectedPreview.value
                if (selected != null) {
                    renderSinglePreview(state, selected)
                }
            }
        }
    }

    private fun updatePreviewSelector(previews: List<String>) {
        selectorAdapter?.clear()
        selectorAdapter?.addAll(previews)
        selectorAdapter?.notifyDataSetChanged()

        binding.previewSelector.isVisible =
            viewModel.displayMode.value == DisplayMode.SINGLE && previews.size > 1

        val selected = viewModel.selectedPreview.value
        if (selected != null) {
            val position = previews.indexOf(selected)
            if (position >= 0) {
                binding.previewSelector.setSelection(position)
            }
        }
    }

    private fun renderAllPreviews(state: PreviewState.Ready) {
        val container = binding.previewListContainer
        val loader = classLoader ?: return

        val functionNames = state.previewConfigs.map { it.functionName }
        LOG.debug("renderAllPreviews called with {} functions: {}", functionNames.size, functionNames)

        val currentFunctions = multiRenderers.keys.toSet()
        val newFunctions = functionNames.toSet()

        if (currentFunctions == newFunctions) {
            LOG.debug("Same functions, re-rendering existing views")
            functionNames.forEach { functionName ->
                multiRenderers[functionName]?.render(
                    dexFile = state.dexFile,
                    className = state.className,
                    functionName = functionName
                )
            }
            return
        }

        LOG.debug("Creating new preview items")
        container.removeAllViews()
        multiRenderers.clear()

        state.previewConfigs.forEachIndexed { index, config ->
            LOG.debug("Adding preview item {}: {}", index, config.functionName)
            val previewItem = createPreviewItem(config.functionName, index == 0)
            container.addView(previewItem)

            val boundedView = previewItem.findViewById<BoundedComposeView>(R.id.composePreview)

            config.heightDp?.let { heightDp ->
                boundedView.explicitHeightPx = (heightDp * resources.displayMetrics.density).toInt()
            }

            boundedView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )

            val renderer = ComposableRenderer(boundedView.composeView, loader)
            multiRenderers[config.functionName] = renderer

            renderer.render(
                dexFile = state.dexFile,
                className = state.className,
                functionName = config.functionName
            )
        }

        LOG.debug("Container now has {} children", container.childCount)
    }

    private fun renderSinglePreview(state: PreviewState.Ready, functionName: String) {
        singleRenderer?.render(
            dexFile = state.dexFile,
            className = state.className,
            functionName = functionName
        )
    }

    private fun createPreviewItem(functionName: String, isFirst: Boolean): View {
        val item = layoutInflater.inflate(R.layout.item_preview_card, binding.previewListContainer, false)

        val label = item.findViewById<TextView>(R.id.previewLabel)
        label.text = "@$functionName"

        val divider = item.findViewById<View>(R.id.divider)
        divider.isVisible = !isFirst

        return item
    }

    override fun onDestroy() {
        super.onDestroy()
        multiRenderers.clear()
        singleRenderer = null
        classLoader?.release()
        classLoader = null
        selectorAdapter = null
        toggleMenuItem = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        classLoader?.release()
        LOG.warn("Low memory - released preview resources")
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewActivity::class.java)

        private const val EXTRA_SOURCE_CODE = "source_code"
        private const val EXTRA_FILE_PATH = "file_path"

        fun start(context: Context, sourceCode: String, filePath: String) {
            val intent = Intent(context, ComposePreviewActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_CODE, sourceCode)
                putExtra(EXTRA_FILE_PATH, filePath)
            }
            context.startActivity(intent)
        }
    }
}

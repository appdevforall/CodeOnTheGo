package com.itsaky.androidide.compose.preview

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.compose.preview.runtime.ComposeClassLoader
import com.itsaky.androidide.compose.preview.runtime.ComposableRenderer
import com.itsaky.androidide.compose.preview.runtime.ProjectResourceContextFactory
import com.itsaky.androidide.compose.preview.ui.BoundedComposeView
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R as ResourcesR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

class ComposePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposePreviewBinding

    private val viewModel: ComposePreviewViewModel by viewModels()

    private var classLoader: ComposeClassLoader? = null
    private var singleRenderer: ComposableRenderer? = null
    private val multiRenderers = mutableMapOf<String, ComposableRenderer>()

    private var loadedClass: Class<*>? = null
    private var loadJob: Job? = null

    private val resourceContextFactory by lazy { ProjectResourceContextFactory(this) }
    private var previewInstances: List<PreviewInstance> = emptyList()
    private var renderedKeys: List<String> = emptyList()

    private var toggleMenuItem: android.view.MenuItem? = null
    private var selectorAdapter: ArrayAdapter<String>? = null
    private var selectedSingleKey: String? = null
    private var suppressSelectionCallback = false

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

        viewModel.initialize(this, filePath, sourceCode)

        EventBus.getDefault().register(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDocumentChanged(event: DocumentChangeEvent) {
        if (filePath.isBlank()) return
        if (event.changedFile.toFile().absolutePath != File(filePath).absolutePath) return
        val newText = event.newText ?: return
        viewModel.onSourceChanged(newText)
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
                if (suppressSelectionCallback) return
                val instance = previewInstances.getOrNull(position) ?: return
                selectedSingleKey = instance.cardKey
                if (viewModel.displayMode.value == DisplayMode.SINGLE) {
                    renderSinglePreview()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSinglePreview() {
        binding.singlePreviewView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        singleRenderer = ComposableRenderer(binding.singlePreviewView)
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
                    viewModel.refreshAfterBuild(this@ComposePreviewActivity)
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
                binding.statusSubtext.isVisible = false
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Initializing -> {
                binding.statusText.text = "Initializing..."
                binding.statusSubtext.isVisible = false
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Compiling -> {
                binding.statusText.text = "Compiling..."
                binding.statusSubtext.isVisible = false
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Building -> {
                binding.statusText.text = "Building project..."
                binding.statusSubtext.text = "First build may take 10-15 minutes"
                binding.statusSubtext.isVisible = true
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.NeedsBuild -> {
                LOG.debug("Build required for multi-file preview support")
            }
            is PreviewState.Empty -> {
                LOG.debug("No preview composables found")
            }
            is PreviewState.Ready -> {
                loadAndRender(state)
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

        refreshSelector()

        val state = viewModel.previewState.value
        if (state is PreviewState.Ready) {
            binding.previewScrollView.isVisible = isAllMode
            binding.singlePreviewView.isVisible = !isAllMode

            if (isAllMode) {
                renderAllPreviews()
            } else {
                renderSinglePreview()
            }
        }
    }

    private fun refreshSelector() {
        val labels = previewInstances.map { it.label }

        suppressSelectionCallback = true
        selectorAdapter?.clear()
        selectorAdapter?.addAll(labels)
        selectorAdapter?.notifyDataSetChanged()
        val currentIndex = previewInstances.indexOfFirst { it.cardKey == selectedSingleKey }
        if (currentIndex >= 0) {
            binding.previewSelector.setSelection(currentIndex)
        }
        suppressSelectionCallback = false

        binding.previewSelector.isVisible =
            viewModel.displayMode.value == DisplayMode.SINGLE && labels.size > 1
    }

    private fun loadAndRender(state: PreviewState.Ready) {
        val loader = classLoader ?: return
        LOG.info("Runtime DEX from state: {}, project DEX files: {}",
            state.runtimeDex?.absolutePath ?: "null", state.projectDexFiles.size)
        loadedClass = null
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                loader.setProjectDexFiles(state.projectDexFiles)
                loader.setRuntimeDex(state.runtimeDex)
                val clazz = loader.loadClass(state.dexFile, state.className)
                val instances = if (clazz == null) emptyList() else buildPreviewInstances(state)
                clazz to instances
            }
            val clazz = result.first
            if (clazz == null) {
                LOG.error("render: failed to load class {}", state.className)
                return@launch
            }
            loadedClass = clazz
            previewInstances = result.second
            if (selectedSingleKey == null || previewInstances.none { it.cardKey == selectedSingleKey }) {
                selectedSingleKey = previewInstances.firstOrNull()?.cardKey
            }
            refreshSelector()
            if (viewModel.displayMode.value == DisplayMode.ALL) {
                renderAllPreviews()
            } else {
                renderSinglePreview()
            }
        }
    }

    private fun buildPreviewInstances(state: PreviewState.Ready): List<PreviewInstance> {
        return state.previewConfigs.flatMap { config ->
            val context = resourceContextFactory.contextFor(state.resourceApk, buildConfiguration(config))
            val provider = config.parameterProvider
            if (provider == null) {
                listOf(PreviewInstance(config, context, null, 0, 1))
            } else {
                val values = resolveParameterValues(state.dexFile, provider, config.parameterLimit)
                if (values.isEmpty()) {
                    listOf(PreviewInstance(config, context, null, 0, 1))
                } else {
                    values.mapIndexed { index, value -> PreviewInstance(config, context, value, index, values.size) }
                }
            }
        }
    }

    private fun buildConfiguration(config: PreviewConfig): Configuration {
        val configuration = Configuration(resources.configuration)
        config.uiMode?.let { uiMode ->
            val typeBits = uiMode and Configuration.UI_MODE_TYPE_MASK
            val nightBits = uiMode and Configuration.UI_MODE_NIGHT_MASK
            var merged = configuration.uiMode
            if (typeBits != 0) {
                merged = (merged and Configuration.UI_MODE_TYPE_MASK.inv()) or typeBits
            }
            if (nightBits != 0) {
                merged = (merged and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
            }
            configuration.uiMode = merged
        }
        config.fontScale?.let { configuration.fontScale = it }
        config.locale?.let { configuration.setLocale(Locale.forLanguageTag(it.replace('_', '-'))) }
        return configuration
    }

    private fun resolveParameterValues(dexFile: File, providerFqn: String, limit: Int): List<Any?> {
        val loader = classLoader ?: return emptyList()
        return try {
            val providerClass = loader.loadClass(dexFile, providerFqn) ?: run {
                LOG.warn("@PreviewParameter provider not found: {}", providerFqn)
                return emptyList()
            }
            val instance = providerClass.getDeclaredConstructor().newInstance()
            val values = providerClass.getMethod("getValues").invoke(instance) as? Sequence<*>
                ?: return emptyList()
            val capped = values.take(minOf(limit, MAX_PARAMETER_VALUES)).toList()
            capped
        } catch (e: Throwable) {
            LOG.error("Failed to resolve @PreviewParameter values from {}", providerFqn, e)
            emptyList()
        }
    }

    private fun renderAllPreviews() {
        val container = binding.previewListContainer
        val clazz = loadedClass ?: return
        val instances = previewInstances
        val keys = instances.map { it.cardKey }

        LOG.debug("renderAllPreviews called with {} previews: {}", keys.size, keys)

        if (keys == renderedKeys && multiRenderers.keys == keys.toSet()) {
            LOG.debug("Same functions, re-rendering existing views")
            instances.forEach { instance ->
                multiRenderers[instance.cardKey]?.render(
                    clazz, instance.config.functionName, instance.context, instance.parameterValue, instance.config.parameterIndex
                )
            }
            return
        }

        LOG.debug("Creating new preview items")
        container.removeAllViews()
        multiRenderers.clear()
        renderedKeys = keys

        instances.forEachIndexed { index, instance ->
            LOG.debug("Adding preview item {}: {}", index, instance.config.functionName)
            val previewItem = createPreviewItem(instance, index == 0)
            container.addView(previewItem)

            val boundedView = previewItem.findViewById<BoundedComposeView>(R.id.composePreview)
            applyCardAttributes(boundedView.composeView, boundedView, instance.config)

            boundedView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )

            val renderer = ComposableRenderer(boundedView.composeView)
            multiRenderers[instance.cardKey] = renderer

            renderer.render(clazz, instance.config.functionName, instance.context, instance.parameterValue, instance.config.parameterIndex)
        }

        LOG.debug("Container now has {} children", container.childCount)
    }

    private fun renderSinglePreview() {
        val clazz = loadedClass ?: return
        val instance = previewInstances.firstOrNull { it.cardKey == selectedSingleKey }
            ?: previewInstances.firstOrNull()
            ?: return
        selectedSingleKey = instance.cardKey
        applyBackground(binding.singlePreviewView, instance.config)
        singleRenderer?.render(clazz, instance.config.functionName, instance.context, instance.parameterValue, instance.config.parameterIndex)
    }

    private fun applyCardAttributes(composeView: View, boundedView: BoundedComposeView, config: PreviewConfig) {
        val density = resources.displayMetrics.density
        boundedView.explicitWidthPx = config.widthDp?.let { (it * density).toInt() }
        boundedView.explicitHeightPx = config.heightDp?.let { (it * density).toInt() }
        applyBackground(composeView, config)
    }

    private fun applyBackground(view: View, config: PreviewConfig) {
        view.setBackgroundColor(
            if (config.showBackground) {
                resolveBackgroundColor(config.backgroundColor)
            } else {
                android.graphics.Color.TRANSPARENT
            }
        )
    }

    private fun resolveBackgroundColor(raw: Long?): Int {
        if (raw == null || raw == 0L) return DEFAULT_PREVIEW_BACKGROUND
        val argb = raw.toInt()
        return if ((argb ushr 24) == 0) argb or OPAQUE_ALPHA else argb
    }

    private fun createPreviewItem(instance: PreviewInstance, isFirst: Boolean): View {
        val item = layoutInflater.inflate(R.layout.item_preview_card, binding.previewListContainer, false)

        item.findViewById<TextView>(R.id.previewLabel)?.let { label ->
            label.text = buildString {
                append(instance.label)
                instance.config.group?.let { append("  ·  ").append(it) }
            }
        }

        item.findViewById<View>(R.id.divider)?.let { divider ->
            divider.isVisible = !isFirst
        }

        return item
    }

    private data class PreviewInstance(
        val config: PreviewConfig,
        val context: Context,
        val parameterValue: Any?,
        val valueIndex: Int,
        val valueCount: Int
    ) {
        val cardKey: String get() = if (valueCount > 1) "${config.key}[$valueIndex]" else config.key
        val label: String get() = if (valueCount > 1) "${config.displayName} [$valueIndex]" else config.displayName
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        loadJob?.cancel()
        loadJob = null
        loadedClass = null
        previewInstances = emptyList()
        renderedKeys = emptyList()
        resourceContextFactory.release()
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
        private val DEFAULT_PREVIEW_BACKGROUND = android.graphics.Color.WHITE
        private const val OPAQUE_ALPHA = 0xFF shl 24
        private const val MAX_PARAMETER_VALUES = 25

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

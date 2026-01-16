package com.itsaky.androidide.compose.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.compose.preview.databinding.FragmentComposePreviewBinding
import com.itsaky.androidide.compose.preview.runtime.ComposeClassLoader
import com.itsaky.androidide.compose.preview.runtime.ComposableRenderer
import com.itsaky.androidide.resources.R as ResourcesR
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ComposePreviewFragment : Fragment() {

    private var _binding: FragmentComposePreviewBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ComposePreviewViewModel by viewModels()

    private var classLoader: ComposeClassLoader? = null
    private var renderer: ComposableRenderer? = null

    private var sourceCode: String = DEFAULT_SOURCE
    private var onNavigateBack: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentComposePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupPreview()
        observeState()

        val filePath = arguments?.getString(ARG_FILE_PATH) ?: ""
        viewModel.initialize(requireContext(), filePath)

        arguments?.getString(ARG_SOURCE_CODE)?.let {
            sourceCode = it
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onNavigateBack?.invoke() ?: parentFragmentManager.popBackStack()
        }
    }

    private fun setupPreview() {
        classLoader = ComposeClassLoader(requireContext())
        renderer = ComposableRenderer(binding.composePreview, classLoader!!)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.previewState.collect { state ->
                    handleState(state)
                }
            }
        }
    }

    private fun handleState(state: PreviewState) {
        binding.loadingIndicator.isVisible = state is PreviewState.Compiling || state is PreviewState.Building
        binding.initializingText.isVisible = state is PreviewState.Initializing ||
            state is PreviewState.Empty ||
            state is PreviewState.NeedsBuild ||
            state is PreviewState.Building
        binding.errorOverlay.isVisible = state is PreviewState.Error
        binding.composePreview.isVisible = state is PreviewState.Ready

        when (state) {
            is PreviewState.Idle -> {
                if (sourceCode.isNotBlank()) {
                    viewModel.compileNow(sourceCode)
                }
            }
            is PreviewState.Initializing -> {
                binding.initializingText.setText(ResourcesR.string.preview_initializing)
            }
            is PreviewState.Empty -> {
                binding.initializingText.setText(ResourcesR.string.preview_empty_title)
            }
            is PreviewState.Compiling -> {
                LOG.debug("Compiling...")
            }
            is PreviewState.Building -> {
                binding.initializingText.setText(ResourcesR.string.preview_building_project)
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.NeedsBuild -> {
                binding.initializingText.setText(ResourcesR.string.preview_build_required_title)
            }
            is PreviewState.Ready -> {
                classLoader?.setProjectDexFiles(state.projectDexFiles)
                classLoader?.setRuntimeDex(state.runtimeDex)
                val config = state.previewConfigs.firstOrNull() ?: return
                renderer?.render(
                    dexFile = state.dexFile,
                    className = state.className,
                    functionName = config.functionName
                )
            }
            is PreviewState.Error -> {
                showError(state)
            }
        }
    }

    private fun showError(state: PreviewState.Error) {
        binding.errorOverlay.isVisible = true
        binding.errorMessage.text = state.message

        val details = state.diagnostics.joinToString("\n") { diagnostic ->
            buildString {
                diagnostic.file?.let { append("$it:") }
                diagnostic.line?.let { append("$it:") }
                diagnostic.column?.let { append("$it ") }
                append("[${diagnostic.severity}] ")
                append(diagnostic.message)
            }
        }
        binding.errorDetails.text = details
        binding.errorDetails.isVisible = details.isNotBlank()
    }

    fun updateSource(source: String) {
        sourceCode = source
        viewModel.onSourceChanged(source)
    }

    fun setNavigateBackListener(listener: () -> Unit) {
        onNavigateBack = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        classLoader?.release()
        classLoader = null
        renderer = null
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        classLoader?.release()
        LOG.warn("Low memory - released preview resources")
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewFragment::class.java)

        private const val ARG_SOURCE_CODE = "source_code"
        private const val ARG_FILE_PATH = "file_path"

        private const val DEFAULT_SOURCE = """
package preview

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun Preview() {
    Text("Hello, Compose Preview!")
}
"""

        fun newInstance(sourceCode: String? = null, filePath: String? = null): ComposePreviewFragment {
            return ComposePreviewFragment().apply {
                arguments = Bundle().apply {
                    sourceCode?.let { putString(ARG_SOURCE_CODE, it) }
                    filePath?.let { putString(ARG_FILE_PATH, it) }
                }
            }
        }
    }
}

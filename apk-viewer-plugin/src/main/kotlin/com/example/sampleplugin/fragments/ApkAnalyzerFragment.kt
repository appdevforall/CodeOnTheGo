package com.example.sampleplugin.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sampleplugin.R
import com.example.sampleplugin.viewmodel.ApkAnalyzerViewModel
import com.example.sampleplugin.viewmodel.ApkParseResult
import com.google.android.material.color.MaterialColors
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.launch

data class TableSection(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val showAllAction: String? = null
)

class ApkAnalyzerFragment : Fragment() {

    companion object {
        private const val PLUGIN_ID = "com.example.apkviewer"
        private const val MAX_LARGE_FILES_DISPLAYED = 10
        private const val FALLBACK_SURFACE = 0xFFFFFBFE.toInt()
        private const val FALLBACK_SURFACE_VARIANT = 0xFFE7E0EC.toInt()
    }

    private val viewModel by viewModels<ApkAnalyzerViewModel>()
    private var showAllLargeFiles = false
    private var pendingAnalysisFile: java.io.File? = null

    private var statusText: TextView? = null
    private var btnStart: Button? = null
    private var progressBar: ProgressBar? = null
    private var resultsContainer: LinearLayout? = null

    private val pickApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { viewModel.analyzeApk(it, requireContext().applicationContext) }
        }
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_sample, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.tv_context)
        btnStart = view.findViewById(R.id.btnStart)
        progressBar = view.findViewById(R.id.progressBar)
        resultsContainer = view.findViewById(R.id.resultsContainer)

        btnStart?.setOnClickListener { openFilePicker() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderState(state) }
            }
        }

        val file = pendingAnalysisFile ?: viewModel.pendingFile
        if (file != null) {
            pendingAnalysisFile = null
            viewModel.pendingFile = null
            viewModel.analyzeApk(file)
        }
    }

    private fun renderState(state: ApkAnalyzerViewModel.UiState) {
        resultsContainer?.removeAllViews()
        when (state) {
            is ApkAnalyzerViewModel.UiState.Idle -> {
                progressBar?.visibility = View.GONE
                statusText?.visibility = View.GONE
                btnStart?.isEnabled = true
            }
            is ApkAnalyzerViewModel.UiState.Analyzing -> {
                showAllLargeFiles = false
                progressBar?.visibility = View.VISIBLE
                statusText?.visibility = View.VISIBLE
                statusText?.text = getString(R.string.analyzing_apk)
                btnStart?.isEnabled = false
            }
            is ApkAnalyzerViewModel.UiState.Success -> {
                progressBar?.visibility = View.GONE
                statusText?.visibility = View.GONE
                btnStart?.isEnabled = true
                renderSections(mapToSections(state.data))
            }
            is ApkAnalyzerViewModel.UiState.Error -> {
                progressBar?.visibility = View.GONE
                statusText?.visibility = View.VISIBLE
                statusText?.text = getString(R.string.analysis_failed, state.message)
                btnStart?.isEnabled = true
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        pickApkLauncher.launch(intent)
    }

    fun analyzeFile(file: java.io.File) {
        if (!isAdded || view == null) {
            pendingAnalysisFile = file
            return
        }
        viewModel.analyzeApk(file)
    }

    private fun formatRatio(compressed: Long, raw: Long): String {
        return if (raw > 0) {
            String.format("%.1f%%", (compressed.toDouble() / raw.toDouble()) * 100)
        } else getString(R.string.value_na)
    }

    private fun mapToSections(data: ApkParseResult): List<TableSection> {
        val sections = mutableListOf<TableSection>()

        sections.add(TableSection(
            title = getString(R.string.section_apk_structure),
            headers = listOf(getString(R.string.header_property), getString(R.string.header_value)),
            rows = listOf(
                listOf(getString(R.string.label_apk_file_size), formatFileSize(data.apkSize)),
                listOf(getString(R.string.label_total_entries), "${data.totalEntries}"),
                listOf(getString(R.string.label_uncompressed_size), formatFileSize(data.totalUncompressed)),
                listOf(getString(R.string.label_compressed_size), formatFileSize(data.totalCompressed)),
                listOf(getString(R.string.label_compression_ratio), formatRatio(data.totalCompressed, data.totalUncompressed)),
                listOf(getString(R.string.label_directories), "${data.directoryCount}"),
                listOf(getString(R.string.label_files), "${data.fileCount}")
            )
        ))

        val keyFileRows = data.keyFiles.map { kf ->
            if (kf.exists) {
                listOf(kf.name, "\u2713", formatFileSize(kf.rawSize), formatFileSize(kf.compressedSize), formatRatio(kf.compressedSize, kf.rawSize))
            } else {
                listOf(kf.name, "\u2717", "\u2014", "\u2014", "\u2014")
            }
        }
        sections.add(TableSection(
            title = getString(R.string.section_key_files),
            headers = listOf(getString(R.string.header_file), "", getString(R.string.header_raw), getString(R.string.header_compressed), getString(R.string.header_ratio)),
            rows = keyFileRows
        ))

        if (data.nativeLibArchitectures.isNotEmpty()) {
            val totalNativeLibs = data.nativeLibArchitectures.values.sumOf { it.size }
            val nativeRows = mutableListOf<List<String>>()
            data.nativeLibArchitectures.forEach { (arch, libs) ->
                val archRaw = libs.sumOf { it.rawSize }
                val archCompressed = libs.sumOf { it.compressedSize }
                nativeRows.add(listOf(
                    "\u25B8 $arch",
                    "${libs.size}",
                    formatFileSize(archRaw),
                    formatFileSize(archCompressed)
                ))
                libs.sortedByDescending { it.rawSize }.take(3).forEach { lib ->
                    nativeRows.add(listOf(
                        "    ${lib.name}",
                        "",
                        formatFileSize(lib.rawSize),
                        formatFileSize(lib.compressedSize)
                    ))
                }
                if (libs.size > 3) {
                    nativeRows.add(listOf("    +${libs.size - 3} more", "", "", ""))
                }
            }
            sections.add(TableSection(
                title = getString(R.string.section_native_libraries, totalNativeLibs),
                headers = listOf(getString(R.string.header_name), getString(R.string.header_count), getString(R.string.header_raw), getString(R.string.header_compressed)),
                rows = nativeRows
            ))
        }

        if (data.resourceDirs.isNotEmpty()) {
            val resDirRows = data.resourceDirs.map { dir ->
                if (dir.rawSize > 0) {
                    listOf(dir.path, "${dir.fileCount}", formatFileSize(dir.rawSize), formatFileSize(dir.compressedSize))
                } else {
                    listOf(dir.path, "0", "\u2014", "\u2014")
                }
            }
            sections.add(TableSection(
                title = getString(R.string.section_resource_directories, data.resourceDirs.size),
                headers = listOf(getString(R.string.header_directory), getString(R.string.header_files), getString(R.string.header_raw), getString(R.string.header_compressed)),
                rows = resDirRows
            ))
        }

        if (data.largeFiles.isNotEmpty()) {
            val displayedFiles = if (showAllLargeFiles) data.largeFiles else data.largeFiles.take(MAX_LARGE_FILES_DISPLAYED)
            val largeFileRows = displayedFiles.map { lf ->
                listOf(lf.name, formatFileSize(lf.rawSize), formatFileSize(lf.compressedSize), formatRatio(lf.compressedSize, lf.rawSize))
            }
            sections.add(TableSection(
                title = getString(R.string.section_large_files),
                headers = listOf(getString(R.string.header_file), getString(R.string.header_raw), getString(R.string.header_compressed), getString(R.string.header_ratio)),
                rows = largeFileRows,
                showAllAction = if (!showAllLargeFiles && data.largeFiles.size > MAX_LARGE_FILES_DISPLAYED) {
                    getString(R.string.action_show_all, data.largeFiles.size)
                } else null
            ))
        }

        val sigText = if (data.hasV1Signature) getString(R.string.value_v1_signing) else getString(R.string.value_v2_or_unsigned)
        sections.add(TableSection(
            title = getString(R.string.section_apk_metadata),
            headers = listOf(getString(R.string.header_property), getString(R.string.header_value)),
            rows = listOf(
                listOf(getString(R.string.label_signature_scheme), sigText),
                listOf(getString(R.string.label_multi_dex), if (data.hasMultiDex) getString(R.string.value_yes) else getString(R.string.value_no)),
                listOf(getString(R.string.label_code_obfuscation), if (data.hasProguard) getString(R.string.value_detected) else getString(R.string.value_none_detected))
            )
        ))

        return sections
    }

    private fun renderSections(sections: List<TableSection>) {
        val container = resultsContainer ?: return
        val inflater = layoutInflater
        val ctx = requireContext()

        val surfaceColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, FALLBACK_SURFACE)
        val surfaceVariantColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant, FALLBACK_SURFACE_VARIANT)
        val altRowColor = ColorUtils.blendARGB(surfaceColor, surfaceVariantColor, 0.4f)

        sections.forEach { section ->
            val cardView = inflater.inflate(R.layout.item_section_card, container, false)
            val cardCtx = cardView.context

            cardView.findViewById<TextView>(R.id.sectionTitle).text = section.title
            val table = cardView.findViewById<TableLayout>(R.id.sectionTable)

            if (section.headers.isNotEmpty()) {
                val headerRow = TableRow(cardCtx).apply {
                    setBackgroundColor(surfaceVariantColor)
                }
                section.headers.forEach { header ->
                    headerRow.addView(
                        TextView(cardCtx, null, 0, R.style.TableHeaderCell).apply {
                            text = header
                        }
                    )
                }
                table.addView(headerRow)
            }

            section.rows.forEachIndexed { index, row ->
                val dataRow = TableRow(cardCtx).apply {
                    if (index % 2 == 1) setBackgroundColor(altRowColor)
                }
                row.forEachIndexed { colIndex, cellText ->
                    dataRow.addView(
                        TextView(cardCtx, null, 0, R.style.TableDataCell).apply {
                            text = cellText
                            if (colIndex > 0 && section.headers.size > 2) {
                                gravity = Gravity.END
                            }
                        }
                    )
                }
                table.addView(dataRow)
            }

            if (section.showAllAction != null) {
                val actionText = TextView(cardCtx).apply {
                    text = section.showAllAction
                    setPadding(16, 12, 16, 12)
                    setTextColor(MaterialColors.getColor(cardCtx, com.google.android.material.R.attr.colorPrimary, 0))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        showAllLargeFiles = true
                        val currentState = viewModel.uiState.value
                        if (currentState is ApkAnalyzerViewModel.UiState.Success) {
                            renderState(currentState)
                        }
                    }
                }
                (cardView as ViewGroup).addView(actionText)
            }

            container.addView(cardView)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", size, units[unitIndex])
    }
}

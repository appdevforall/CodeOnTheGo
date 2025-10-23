package com.example.sampleplugin.fragments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.sampleplugin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService
import java.util.zip.ZipFile

class ApkAnalyzerFragment : Fragment() {
    companion object {
        private const val PLUGIN_ID = "com.example.sampleplugin"
        private const val ARG_CONTEXT_TYPE = "context_type"

        /**
         * Create a new instance of the fragment for a specific context
         */
        fun newInstance(contextType: String): ApkAnalyzerFragment {
            val fragment = ApkAnalyzerFragment()
            val args = Bundle()
            args.putString(ARG_CONTEXT_TYPE, contextType)
            fragment.arguments = args
            return fragment
        }
    }

    // Service references
    private var projectService: IdeProjectService? = null
    private var uiService: IdeUIService? = null
    private var tooltipService: IdeTooltipService? = null
    private var fileService: IdeFileService? = null
    private var buildService: IdeBuildService? = null

    private var contextText: TextView? = null
    private var btnStart: Button? = null
    private var progressBar: ProgressBar? = null

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                analyzeApkInBackground(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // Get services from the plugin's service registry
        runCatching {
            val serviceRegistry = PluginFragmentHelper.getServiceRegistry(ApkAnalyzerFragment.PLUGIN_ID)
            projectService = serviceRegistry?.get(IdeProjectService::class.java)
            uiService = serviceRegistry?.get(IdeUIService::class.java)
            tooltipService = serviceRegistry?.get(IdeTooltipService::class.java)
            fileService = serviceRegistry?.get(IdeFileService::class.java)
            buildService = serviceRegistry?.get(IdeBuildService::class.java)
        }
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(ApkAnalyzerFragment.PLUGIN_ID, inflater)
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

        contextText = view.findViewById<TextView>(R.id.tv_context)
        btnStart = view.findViewById<Button>(R.id.btnStart)
        progressBar = view.findViewById<ProgressBar>(R.id.progressBar)

        updateContent(view)
        setupClickListeners()
    }

    private fun updateContent(view: View) {
        // Update context display
        contextText?.text =
            "This is a test fragment for the APK Analyzer plugin."

    }

    private fun setupClickListeners() {
        btnStart?.setOnClickListener {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        pickApkLauncher.launch(intent)
    }

    private fun analyzeApkInBackground(uri: Uri) {
        progressBar?.visibility = View.VISIBLE
        btnStart?.isEnabled = false
        contextText?.text = "Analyzing APK..."

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    analyzeApkStructure(uri)
                }
            }.getOrElse { e ->
                "Failed to analyze APK: ${e.message}"
            }

            progressBar?.visibility = View.GONE
            btnStart?.isEnabled = true
            contextText?.text = result
        }
    }

    private fun analyzeApkStructure(uri : Uri): String {
        val result = StringBuilder()

        // Create a temporary file to copy the APK content
        val tempFile = java.io.File.createTempFile("apk_", ".apk", context?.cacheDir)

        return runCatching {
            // Copy the content from the URI to the temp file
            context?.contentResolver?.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val zipFile = ZipFile(tempFile)

        result.append(" APK STRUCTURE:\n")

        val entries = zipFile.entries().toList().sortedBy { it.name }
        val explicitDirectories = mutableSetOf<String>()
        val implicitDirectories = mutableSetOf<String>()
        val files = mutableListOf<String>()
        val nativeLibs = mutableListOf<String>()

        var totalUncompressedSize = 0L
        var totalCompressedSize = 0L
        var totalEntries = 0
        val apkFileSize = tempFile.length()

        entries.forEach { entry ->
            totalEntries++
            totalUncompressedSize += entry.size
            totalCompressedSize += entry.compressedSize

            if (entry.isDirectory) {
                explicitDirectories.add(entry.name)
            } else {
                files.add(entry.name)

                // Add implicit directories from file paths
                val pathParts = entry.name.split("/")
                if (pathParts.size > 1) {
                    for (i in 1 until pathParts.size) {
                        val dirPath = pathParts.subList(0, i).joinToString("/") + "/"
                        implicitDirectories.add(dirPath)
                    }
                }

                // Check for native libraries
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    nativeLibs.add(entry.name)
                }
            }
        }

        // Calculate total directories (explicit + implicit, removing duplicates)
        val allDirectories = explicitDirectories.union(implicitDirectories)

        result.append("• APK File Size: ${formatFileSize(apkFileSize)}\n")
        result.append("• Total Entries: $totalEntries\n")
        result.append("• Total Uncompressed Size: ${formatFileSize(totalUncompressedSize)}\n")
        result.append("• Total Compressed Size: ${formatFileSize(totalCompressedSize)}\n")
        result.append("• Compression Ratio: ${String.format("%.1f%%", (totalCompressedSize.toDouble() / totalUncompressedSize.toDouble()) * 100)}\n")
        result.append("• Directories: ${allDirectories.size} (${explicitDirectories.size} explicit, ${implicitDirectories.size} implicit)\n")
        result.append("• Files: ${files.size}\n\n")

        // Key files with both compressed and uncompressed sizes
        result.append(" KEY FILES:\n")
        val keyFiles = listOf(
            "AndroidManifest.xml",
            "classes.dex",
            "classes2.dex",
            "classes3.dex",
            "resources.arsc",
            "META-INF/MANIFEST.MF"
        )

        keyFiles.forEach { keyFile ->
            val exists = files.any { it == keyFile }
            if (exists) {
                val entry = entries.find { it.name == keyFile }
                val uncompressedSize = entry?.size?.let { formatFileSize(it) } ?: "?"
                val compressedSize = entry?.compressedSize?.let { formatFileSize(it) } ?: "?"
                val compressionRatio = if (entry != null && entry.size > 0) {
                    String.format("%.1f%%", (entry.compressedSize.toDouble() / entry.size.toDouble()) * 100)
                } else "N/A"
                result.append("• $keyFile: ✓ Raw: $uncompressedSize, Compressed: $compressedSize ($compressionRatio)\n")
            } else {
                result.append("• $keyFile: ✗\n")
            }
        }

        result.append("\n")

        // Native libraries with detailed size analysis
        if (nativeLibs.isNotEmpty()) {
            result.append(" NATIVE LIBRARIES (${nativeLibs.size}):\n")
            val archMap = mutableMapOf<String, MutableList<Pair<String, Pair<Long, Long>>>>()

            nativeLibs.forEach { lib ->
                val parts = lib.split("/")
                if (parts.size >= 3) {
                    val arch = parts[1]
                    val libName = parts.last()
                    val entry = entries.find { it.name == lib }
                    val sizes = Pair(entry?.size ?: 0L, entry?.compressedSize ?: 0L)
                    archMap.getOrPut(arch) { mutableListOf() }.add(Pair(libName, sizes))
                }
            }

            archMap.forEach { (arch, libs) ->
                val totalUncompressed = libs.sumOf { it.second.first }
                val totalCompressed = libs.sumOf { it.second.second }
                result.append("• $arch (${libs.size} libs) - Raw: ${formatFileSize(totalUncompressed)}, Compressed: ${formatFileSize(totalCompressed)}\n")

                // Show largest libraries for this architecture
                libs.sortedByDescending { it.second.first }.take(3).forEach { (libName, sizes) ->
                    if (sizes.first > 0) {
                        result.append("  • $libName: ${formatFileSize(sizes.first)} / ${formatFileSize(sizes.second)}\n")
                    } else {
                        result.append("  • $libName\n")
                    }
                }
                if (libs.size > 3) {
                    result.append("  • ... and ${libs.size - 3} more libraries\n")
                }
            }
            result.append("\n")
        }

        // Resource directories with sizes
        val resourceDirs = allDirectories.filter { it.startsWith("res/") }.sorted()
        if (resourceDirs.isNotEmpty()) {
            result.append(" RESOURCE DIRECTORIES (${resourceDirs.size}):\n")
            resourceDirs.forEach { dir ->
                // Calculate total size for files in this directory
                val dirFiles = files.filter { it.startsWith(dir) && it.count { c -> c == '/' } == dir.count { c -> c == '/' } }
                val dirUncompressedSize = dirFiles.sumOf { fileName -> entries.find { it.name == fileName }?.size ?: 0L }
                val dirCompressedSize = dirFiles.sumOf { fileName -> entries.find { it.name == fileName }?.compressedSize ?: 0L }

                if (dirUncompressedSize > 0) {
                    result.append("• $dir (${dirFiles.size} files) - Raw: ${formatFileSize(dirUncompressedSize)}, Compressed: ${formatFileSize(dirCompressedSize)}\n")
                } else {
                    result.append("• $dir\n")
                }
            }
            result.append("\n")
        }

        // Large files analysis (files > 100KB)
        val largeFiles = files.mapNotNull { fileName ->
            entries.find { it.name == fileName }?.let { entry ->
                if (entry.size > 100 * 1024) {
                    Triple(fileName, entry.size, entry.compressedSize)
                } else null
            }
        }.sortedByDescending { it.second }

        if (largeFiles.isNotEmpty()) {
            result.append(" LARGE FILES (>100KB, ${largeFiles.size} files):\n")
            largeFiles.take(10).forEach { (fileName, uncompressed, compressed) ->
                val compressionRatio = if (uncompressed > 0) {
                    String.format("%.1f%%", (compressed.toDouble() / uncompressed.toDouble()) * 100)
                } else "N/A"
                result.append("• $fileName - Raw: ${formatFileSize(uncompressed)}, Compressed: ${formatFileSize(compressed)} ($compressionRatio)\n")
            }
            if (largeFiles.size > 10) {
                result.append("• ... and ${largeFiles.size - 10} more large files\n")
            }
            result.append("\n")
        }

        // APK metadata analysis
        result.append(" APK METADATA:\n")

        // Check for APK signature scheme
        val hasV1Signature = files.any { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA")) }
        // Note: APK v2/v3 signatures are stored in the APK Signing Block, not as ZIP entries
        // We can only reliably detect v1 signatures from ZIP file entries
        result.append("• APK Signature Scheme: ")
        if (hasV1Signature) {
            result.append("v1 (JAR signing) detected\n")
        } else {
            result.append("v2+ or unsigned (v2+ signatures not detectable from ZIP entries)\n")
        }

        // Check for multi-APK indicators
        val hasMultipleDex = files.count { it.matches(Regex("classes\\d*\\.dex")) } > 1
        result.append("• Multi-DEX: ${if (hasMultipleDex) "Yes" else "No"}\n")

        // Check for common optimization indicators
        val hasProguard = files.any { it == "proguard/mappings.txt" } || files.any { it.contains("mapping.txt") }
        result.append("• Code Obfuscation: ${if (hasProguard) "Detected" else "None detected"}\n")

        zipFile.close()

            result.toString()
        }.fold(
            onSuccess = {
                tempFile.delete()
                it
            },
            onFailure = { e ->
                tempFile.delete()
                "Failed to analyze APK: ${e.message}"
            }
        )
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



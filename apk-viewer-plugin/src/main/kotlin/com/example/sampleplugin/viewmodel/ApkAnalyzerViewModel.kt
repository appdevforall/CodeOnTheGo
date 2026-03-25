package com.example.sampleplugin.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private val ZipEntry.safeSize: Long get() = size.coerceAtLeast(0L)
private val ZipEntry.safeCompressedSize: Long get() = compressedSize.coerceAtLeast(0L)

class ApkAnalyzerViewModel : ViewModel() {

    companion object {
        private val DEX_FILE_PATTERN = Regex("classes\\d*\\.dex")
    }

    sealed interface UiState {
        data object Idle : UiState
        data object Analyzing : UiState
        data class Success(val data: ApkParseResult) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    var pendingFile: java.io.File? = null
    private var analysisJob: Job? = null

    fun analyzeApk(file: java.io.File) = launchAnalysis { parseApk(file) }

    fun analyzeApk(uri: Uri, context: Context) = launchAnalysis {
        val tempFile = java.io.File.createTempFile("apk_", ".apk", context.cacheDir)
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Failed to open InputStream for URI: $uri")
            inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            parseApk(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun launchAnalysis(block: suspend () -> ApkParseResult) {
        analysisJob?.cancel()
        _uiState.value = UiState.Analyzing
        analysisJob = viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { block() }
                _uiState.value = UiState.Success(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun parseApk(file: java.io.File): ApkParseResult {
        return ZipFile(file).use { zipFile ->
            val entries = zipFile.entries().toList().sortedBy { it.name }
            val entryMap = entries.associateBy { it.name }
            val explicitDirs = mutableSetOf<String>()
            val implicitDirs = mutableSetOf<String>()
            val fileNames = mutableListOf<String>()
            val nativeLibPaths = mutableListOf<String>()

            var totalUncompressed = 0L
            var totalCompressed = 0L
            var totalEntries = 0
            val apkSize = file.length()

            entries.forEach { entry ->
                totalEntries++
                totalUncompressed += entry.safeSize
                totalCompressed += entry.safeCompressedSize
                if (entry.isDirectory) {
                    explicitDirs.add(entry.name)
                } else {
                    fileNames.add(entry.name)
                    val parts = entry.name.split("/")
                    if (parts.size > 1) {
                        for (i in 1 until parts.size) {
                            implicitDirs.add(parts.subList(0, i).joinToString("/") + "/")
                        }
                    }
                    if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                        nativeLibPaths.add(entry.name)
                    }
                }
            }

            val allDirs = explicitDirs.union(implicitDirs)

            val keyFileNames = listOf(
                "AndroidManifest.xml", "classes.dex", "classes2.dex",
                "classes3.dex", "resources.arsc", "META-INF/MANIFEST.MF"
            )
            val keyFiles = keyFileNames.map { name ->
                val entry = entryMap[name]
                KeyFileInfo(
                    name = name,
                    exists = entry != null,
                    rawSize = entry?.safeSize ?: 0L,
                    compressedSize = entry?.safeCompressedSize ?: 0L
                )
            }

            val archMap = mutableMapOf<String, MutableList<NativeLibInfo>>()
            nativeLibPaths.forEach { lib ->
                val parts = lib.split("/")
                if (parts.size >= 3) {
                    val arch = parts[1]
                    val entry = entryMap[lib]
                    archMap.getOrPut(arch) { mutableListOf() }.add(
                        NativeLibInfo(
                            name = parts.last(),
                            rawSize = entry?.safeSize ?: 0L,
                            compressedSize = entry?.safeCompressedSize ?: 0L
                        )
                    )
                }
            }

            val filesByParentDir = fileNames.groupBy { name ->
                val lastSlash = name.lastIndexOf('/')
                if (lastSlash >= 0) name.substring(0, lastSlash + 1) else ""
            }
            val resDirs = allDirs.filter { it.startsWith("res/") }.sorted().map { dir ->
                val dirFiles = filesByParentDir[dir] ?: emptyList()
                ResourceDirInfo(
                    path = dir,
                    fileCount = dirFiles.size,
                    rawSize = dirFiles.sumOf { name -> entryMap[name]?.safeSize ?: 0L },
                    compressedSize = dirFiles.sumOf { name -> entryMap[name]?.safeCompressedSize ?: 0L }
                )
            }

            val largeFiles = fileNames.mapNotNull { name ->
                entryMap[name]?.let { entry ->
                    if (entry.safeSize > 100 * 1024) {
                        LargeFileInfo(name, entry.safeSize, entry.safeCompressedSize)
                    } else null
                }
            }.sortedByDescending { it.rawSize }

            ApkParseResult(
                apkSize = apkSize,
                totalEntries = totalEntries,
                totalUncompressed = totalUncompressed,
                totalCompressed = totalCompressed,
                directoryCount = allDirs.size,
                fileCount = fileNames.size,
                keyFiles = keyFiles,
                nativeLibArchitectures = archMap,
                resourceDirs = resDirs,
                largeFiles = largeFiles.take(10),
                totalLargeFiles = largeFiles.size,
                hasV1Signature = fileNames.any {
                    it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA"))
                },
                hasMultiDex = fileNames.count { it.matches(DEX_FILE_PATTERN) } > 1,
                hasProguard = fileNames.any { it == "proguard/mappings.txt" } ||
                        fileNames.any { it.contains("mapping.txt") }
            )
        }
    }
}

data class KeyFileInfo(
    val name: String,
    val exists: Boolean,
    val rawSize: Long,
    val compressedSize: Long
)

data class NativeLibInfo(
    val name: String,
    val rawSize: Long,
    val compressedSize: Long
)

data class ResourceDirInfo(
    val path: String,
    val fileCount: Int,
    val rawSize: Long,
    val compressedSize: Long
)

data class LargeFileInfo(
    val name: String,
    val rawSize: Long,
    val compressedSize: Long
)

data class ApkParseResult(
    val apkSize: Long,
    val totalEntries: Int,
    val totalUncompressed: Long,
    val totalCompressed: Long,
    val directoryCount: Int,
    val fileCount: Int,
    val keyFiles: List<KeyFileInfo>,
    val nativeLibArchitectures: Map<String, List<NativeLibInfo>>,
    val resourceDirs: List<ResourceDirInfo>,
    val largeFiles: List<LargeFileInfo>,
    val totalLargeFiles: Int,
    val hasV1Signature: Boolean,
    val hasMultiDex: Boolean,
    val hasProguard: Boolean
)
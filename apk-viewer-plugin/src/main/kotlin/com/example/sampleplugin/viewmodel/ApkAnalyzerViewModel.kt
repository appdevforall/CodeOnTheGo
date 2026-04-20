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
        private const val LARGE_FILE_THRESHOLD_BYTES = 100L * 1024
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
            val classified = classifyEntries(entries)
            val allDirs = classified.explicitDirs.union(classified.implicitDirs)

            val largeFiles = buildLargeFiles(classified.fileNames, entryMap)

            ApkParseResult(
                apkSize = file.length(),
                totalEntries = classified.totalEntries,
                totalUncompressed = classified.totalUncompressed,
                totalCompressed = classified.totalCompressed,
                directoryCount = allDirs.size,
                fileCount = classified.fileNames.size,
                keyFiles = buildKeyFiles(entryMap),
                nativeLibArchitectures = buildNativeLibMap(classified.nativeLibPaths, entryMap),
                resourceDirs = buildResourceDirs(allDirs, classified.fileNames, entryMap),
                largeFiles = largeFiles,
                hasV1Signature = classified.fileNames.any {
                    it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA"))
                },
                hasMultiDex = classified.fileNames.count { it.matches(DEX_FILE_PATTERN) } > 1,
                hasProguard = classified.fileNames.any { it == "proguard/mappings.txt" } ||
                        classified.fileNames.any { it.contains("mapping.txt") }
            )
        }
    }

    private fun classifyEntries(entries: List<ZipEntry>): EntryClassification {
        val explicitDirs = mutableSetOf<String>()
        val implicitDirs = mutableSetOf<String>()
        val fileNames = mutableListOf<String>()
        val nativeLibPaths = mutableListOf<String>()
        var totalUncompressed = 0L
        var totalCompressed = 0L

        entries.forEach { entry ->
            totalUncompressed += entry.safeSize
            totalCompressed += entry.safeCompressedSize

            if (entry.isDirectory) {
                explicitDirs.add(entry.name)
                return@forEach
            }

            fileNames.add(entry.name)
            collectImplicitDirs(entry.name, implicitDirs)

            if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                nativeLibPaths.add(entry.name)
            }
        }

        return EntryClassification(
            explicitDirs = explicitDirs,
            implicitDirs = implicitDirs,
            fileNames = fileNames,
            nativeLibPaths = nativeLibPaths,
            totalEntries = entries.size,
            totalUncompressed = totalUncompressed,
            totalCompressed = totalCompressed
        )
    }

    private fun collectImplicitDirs(name: String, dirs: MutableSet<String>) {
        val parts = name.split("/")
        for (i in 1 until parts.size) {
            dirs.add(parts.subList(0, i).joinToString("/") + "/")
        }
    }

    private fun buildKeyFiles(entryMap: Map<String, ZipEntry>): List<KeyFileInfo> {
        val keyFileNames = listOf(
            "AndroidManifest.xml", "classes.dex", "classes2.dex",
            "classes3.dex", "resources.arsc", "META-INF/MANIFEST.MF"
        )
        return keyFileNames.map { name ->
            val entry = entryMap[name]
            KeyFileInfo(
                name = name,
                exists = entry != null,
                rawSize = entry?.safeSize ?: 0L,
                compressedSize = entry?.safeCompressedSize ?: 0L
            )
        }
    }

    private fun buildNativeLibMap(
        nativeLibPaths: List<String>,
        entryMap: Map<String, ZipEntry>
    ): Map<String, List<NativeLibInfo>> {
        val archMap = mutableMapOf<String, MutableList<NativeLibInfo>>()
        nativeLibPaths.forEach { lib ->
            val parts = lib.split("/")
            if (parts.size < 3) return@forEach
            val entry = entryMap[lib]
            archMap.getOrPut(parts[1]) { mutableListOf() }.add(
                NativeLibInfo(
                    name = parts.last(),
                    rawSize = entry?.safeSize ?: 0L,
                    compressedSize = entry?.safeCompressedSize ?: 0L
                )
            )
        }
        return archMap
    }

    private fun buildResourceDirs(
        allDirs: Set<String>,
        fileNames: List<String>,
        entryMap: Map<String, ZipEntry>
    ): List<ResourceDirInfo> {
        val filesByParentDir = fileNames.groupBy { name ->
            val lastSlash = name.lastIndexOf('/')
            if (lastSlash >= 0) name.substring(0, lastSlash + 1) else ""
        }
        return allDirs.filter { it.startsWith("res/") }.sorted().map { dir ->
            val dirFiles = filesByParentDir[dir] ?: emptyList()
            ResourceDirInfo(
                path = dir,
                fileCount = dirFiles.size,
                rawSize = dirFiles.sumOf { name -> entryMap[name]?.safeSize ?: 0L },
                compressedSize = dirFiles.sumOf { name -> entryMap[name]?.safeCompressedSize ?: 0L }
            )
        }
    }

    private fun buildLargeFiles(
        fileNames: List<String>,
        entryMap: Map<String, ZipEntry>
    ): List<LargeFileInfo> {
        return fileNames.mapNotNull { name ->
            val entry = entryMap[name] ?: return@mapNotNull null
            if (entry.safeSize > LARGE_FILE_THRESHOLD_BYTES) LargeFileInfo(name, entry.safeSize, entry.safeCompressedSize) else null
        }.sortedByDescending { it.rawSize }
    }
}

private data class EntryClassification(
    val explicitDirs: Set<String>,
    val implicitDirs: Set<String>,
    val fileNames: List<String>,
    val nativeLibPaths: List<String>,
    val totalEntries: Int,
    val totalUncompressed: Long,
    val totalCompressed: Long
)

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
    val hasV1Signature: Boolean,
    val hasMultiDex: Boolean,
    val hasProguard: Boolean
)
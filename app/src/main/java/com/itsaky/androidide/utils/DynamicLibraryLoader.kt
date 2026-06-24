package com.itsaky.androidide.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipInputStream

object DynamicLibraryLoader {

    private const val LLAMA_LIB_VERSION = 5 // Increment this if you update the AAR
    private const val PREFS_NAME = "dynamic_libs"
    private const val PREFS_KEY = "llama_lib_version"

    fun getLlamaClassLoader(context: Context): ClassLoader? {
        ensureLatestLlamaAar(context)
        val extractedAarFile =
            File(context.getDir("dynamic_libs", Context.MODE_PRIVATE), "llama.aar")
        if (!extractedAarFile.exists()) {
            Log.e("DynamicLoad", "Llama AAR not found. Did asset installation run?")
            return null
        }

        // 1. Use a private, versioned directory for the UNZIPPED code.
        // This is separate from the codeCache.
        val baseUnzipDir = context.getDir("llama_unzipped", Context.MODE_PRIVATE)
        val versionedUnzipDir = File(baseUnzipDir, "v$LLAMA_LIB_VERSION")

        // 2. Use a different directory for the OPTIMIZED dex files.
        val optimizedDir = File(context.codeCacheDir, "llama_opt")

        if (!versionedUnzipDir.exists()) {
            Log.i("DynamicLoad", "Unzipping Llama AAR for first use...")
            baseUnzipDir.deleteRecursively()
            versionedUnzipDir.mkdirs()
            try {
                // Normalize and make versionedUnzipDir absolute for secure path validation
                val normalizedUnzipDir = versionedUnzipDir.toPath().toAbsolutePath().normalize()
                
                ZipInputStream(extractedAarFile.inputStream()).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        // Validate entry name doesn't contain dangerous patterns
                        if (entry.name.contains("..") || entry.name.startsWith("/") || entry.name.startsWith("\\")) {
                            throw IllegalStateException("Zip entry contains dangerous path components: ${entry.name}")
                        }
                        
                        // Resolve entry name against base path and normalize
                        val outputPath = normalizedUnzipDir.resolve(entry.name).normalize()
                        
                        // Use Path.startsWith() for proper path validation instead of string comparison
                        if (!outputPath.startsWith(normalizedUnzipDir)) {
                            // DO NOT allow extraction to outside of the target dir
                            throw IllegalStateException("Entry is outside of the target dir: ${entry.name}")
                        }
                        
                        val outputFile = outputPath.toFile()
                        outputFile.parentFile?.mkdirs()
                        if (!entry.isDirectory) {
                            outputFile.outputStream().use { fos -> zipStream.copyTo(fos) }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            } catch (e: Exception) {
                Log.e("DynamicLoad", "Failed to unzip the installed Llama AAR", e)
                baseUnzipDir.deleteRecursively()
                return null
            }
        }

        val dexFile = File(versionedUnzipDir, "classes.dex")
        val abi = Build.SUPPORTED_ABIS[0]
        val nativeLibDir = File(versionedUnzipDir, "jni/$abi")

        if (!dexFile.exists() || !nativeLibDir.exists()) {
            Log.e("DynamicLoad", "AAR contents are invalid (classes.dex or native libs missing).")
            baseUnzipDir.deleteRecursively()
            return null
        }

        // Ensure the optimized directory exists and is clean
        optimizedDir.deleteRecursively()
        optimizedDir.mkdirs()

        return DexClassLoader(
            dexFile.absolutePath,
            optimizedDir.absolutePath, // Use the dedicated optimized directory
            nativeLibDir.absolutePath,
            context.classLoader
        )
    }

    private fun ensureLatestLlamaAar(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getInt(PREFS_KEY, -1)
        if (storedVersion == LLAMA_LIB_VERSION) {
            return
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val assetName = when {
            abi.contains("arm64") || abi.contains("aarch64") -> "llama-v8.aar"
            abi.contains("arm") -> "llama-v7.aar"
            else -> null
        }
        if (assetName == null) {
            Log.e("DynamicLoad", "Unsupported ABI for llama assets: $abi")
            return
        }

        val candidates = listOf(
            "dynamic_libs/${assetName}.br",
            "dynamic_libs/${assetName}",
        )

        val destDir = context.getDir("dynamic_libs", Context.MODE_PRIVATE)
        destDir.mkdirs()
        val destFile = File(destDir, "llama.aar")

        val opened = candidates.firstNotNullOfOrNull { path ->
            try {
                path to context.assets.open(path)
            } catch (_: Exception) {
                null
            }
        }

        if (opened == null) {
            Log.e("DynamicLoad", "Llama AAR asset not found. Tried $candidates")
            return
        }

        val (assetPath, stream) = opened
        Log.i("DynamicLoad", "Refreshing llama AAR from $assetPath")

        val inputStream = if (assetPath.endsWith(".br")) BrotliInputStream(stream) else stream

        inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Force re-unzip and re-optimize on next load
        val baseUnzipDir = context.getDir("llama_unzipped", Context.MODE_PRIVATE)
        val optimizedDir = File(context.codeCacheDir, "llama_opt")
        baseUnzipDir.deleteRecursively()
        optimizedDir.deleteRecursively()

        prefs.edit().putInt(PREFS_KEY, LLAMA_LIB_VERSION).apply()
    }
}

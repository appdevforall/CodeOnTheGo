package com.itsaky.androidide.utils

import android.content.Context
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipInputStream

object DynamicLibraryLoader {

    private const val LLAMA_LIB_VERSION = 2 // Increment this if you update the AAR

    fun getLlamaClassLoader(context: Context): ClassLoader? {
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
}

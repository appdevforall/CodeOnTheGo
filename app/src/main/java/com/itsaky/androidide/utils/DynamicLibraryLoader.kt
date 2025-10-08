// In a file like: app/src/main/java/com/itsaky/androidide/utils/DynamicLibraryLoader.kt

package com.itsaky.androidide.utils

import android.content.Context
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipInputStream

object DynamicLibraryLoader {

    private const val LLAMA_LIB_VERSION = 1 // Increment this if you update the AAR

    fun getLlamaClassLoader(context: Context): ClassLoader? {
        // 1. Find the AAR file that the installer already extracted.
        val extractedAarFile =
            File(context.getDir("dynamic_libs", Context.MODE_PRIVATE), "llama.aar")
        if (!extractedAarFile.exists()) {
            Log.e(
                "DynamicLoad",
                "Llama AAR not found at its installed location. Did the asset installation run?"
            )
            return null
        }

        // 2. Unzip the AAR into a versioned 'codeCache' directory.
        //    This is where the .so files and classes.jar will live.
        val baseDir = File(context.codeCacheDir, "llama_runtime")
        val versionedDir = File(baseDir, "v$LLAMA_LIB_VERSION")

        if (!versionedDir.exists()) {
            Log.i("DynamicLoad", "Unzipping Llama AAR for first use...")
            baseDir.deleteRecursively()
            versionedDir.mkdirs()
            try {
                ZipInputStream(extractedAarFile.inputStream()).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val outputFile = File(versionedDir, entry.name)
                        outputFile.parentFile?.mkdirs()
                        if (!entry.isDirectory) {
                            outputFile.outputStream().use { it.write(zipStream.readBytes()) }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            } catch (e: Exception) {
                Log.e("DynamicLoad", "Failed to unzip the installed Llama AAR", e)
                baseDir.deleteRecursively() // Clean up failed attempt
                return null
            }
        }

        // 3. Create the ClassLoader pointing to the unzipped contents.
        val jarFile = File(versionedDir, "classes.jar")
        val optimizedDir = File(versionedDir, "dex_opt").also { it.mkdirs() }
        val abi = Build.SUPPORTED_ABIS[0]
        val nativeLibPath = File(versionedDir, "jni/$abi").absolutePath

        if (!jarFile.exists() || !File(nativeLibPath).exists()) {
            Log.e("DynamicLoad", "AAR contents are invalid (classes.jar or native libs missing).")
            return null
        }

        return DexClassLoader(
            jarFile.absolutePath,
            optimizedDir.absolutePath,
            nativeLibPath,
            context.classLoader
        )
    }
}
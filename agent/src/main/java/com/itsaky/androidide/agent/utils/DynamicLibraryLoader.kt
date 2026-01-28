package com.itsaky.androidide.agent.utils

import android.content.Context
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipInputStream

object DynamicLibraryLoader {

    private const val LLAMA_LIB_VERSION = 1 // Increment this if you update the AAR
    @Volatile
    private var cachedClassLoader: ClassLoader? = null
    @Volatile
    private var cachedVersion: Int = 0

    fun getLlamaClassLoader(context: Context): ClassLoader? {
        cachedClassLoader?.let { loader ->
            if (cachedVersion == LLAMA_LIB_VERSION) {
                return loader
            }
        }

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
                ZipInputStream(extractedAarFile.inputStream()).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val outputFile = File(versionedUnzipDir, entry.name)
                        outputFile.parentFile?.mkdirs()
                        if (!entry.isDirectory) {
                            outputFile.outputStream().use { fos -> zipStream.copyTo(fos) }
                            // Make DEX files read-only (required by Android 10+)
                            if (entry.name.endsWith(".dex")) {
                                outputFile.setReadOnly()
                                Log.d("DynamicLoad", "Set ${entry.name} as read-only")
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
                Log.i("DynamicLoad", "Successfully unzipped Llama AAR")
            } catch (e: Exception) {
                Log.e("DynamicLoad", "Failed to unzip the installed Llama AAR", e)
                baseUnzipDir.deleteRecursively()
                return null
            }
        }

        val dexFile = File(versionedUnzipDir, "classes.dex")
        val abi = Build.SUPPORTED_ABIS[0]
        val nativeLibDir = File(versionedUnzipDir, "jni/$abi")

        Log.d(
            "DynamicLoad",
            "Checking dex file: ${dexFile.absolutePath} exists=${dexFile.exists()}"
        )
        Log.d(
            "DynamicLoad",
            "Checking native lib dir: ${nativeLibDir.absolutePath} exists=${nativeLibDir.exists()}"
        )

        if (!dexFile.exists() || !nativeLibDir.exists()) {
            Log.e("DynamicLoad", "AAR contents are invalid (classes.dex or native libs missing).")
            Log.e(
                "DynamicLoad",
                "dexFile.exists()=${dexFile.exists()}, nativeLibDir.exists()=${nativeLibDir.exists()}"
            )
            baseUnzipDir.deleteRecursively()
            return null
        }

        // Ensure the optimized directory exists
        if (!optimizedDir.exists()) {
            optimizedDir.mkdirs()
        }

        Log.d(
            "DynamicLoad",
            "Creating DexClassLoader with dex=${dexFile.absolutePath}, opt=${optimizedDir.absolutePath}, native=${nativeLibDir.absolutePath}"
        )

        return try {
            val classLoader = ChildFirstDexClassLoader(
                dexFile.absolutePath,
                optimizedDir.absolutePath,
                nativeLibDir.absolutePath,
                context.classLoader
            )
            Log.i("DynamicLoad", "DexClassLoader created successfully")
            cachedClassLoader = classLoader
            cachedVersion = LLAMA_LIB_VERSION
            classLoader
        } catch (e: Exception) {
            Log.e("DynamicLoad", "Failed to create DexClassLoader", e)
            null
        }
    }
}

private class ChildFirstDexClassLoader(
    dexPath: String,
    optimizedDirectory: String,
    librarySearchPath: String,
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("android.llama.cpp.")) {
            try {
                val clazz = findClass(name)
                if (resolve) {
                    resolveClass(clazz)
                }
                return clazz
            } catch (_: ClassNotFoundException) {
                // Fall back to parent.
            }
        }
        return super.loadClass(name, resolve)
    }
}

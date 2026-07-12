package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.ModuleContext
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.util.Properties

/**
 * Host-side resolver that turns a source file path into a flattened [ModuleContext]
 * for plugins.
 */
internal object ModuleContextResolver {

    fun resolve(filePath: String): ModuleContext? {
        if (filePath.isBlank()) return null

        val file = File(filePath)
        val module = IProjectManager.getInstance().findModuleForFile(file) ?: return null

        val intermediateClasspaths = module.getIntermediateClasspaths()
        val compileClasspaths = (module.getCompileClasspaths() + intermediateClasspaths).distinct()
        val runtimeDexFiles = module.getRuntimeDexFiles().toList()
        val androidModule = module as? AndroidModule
        val variantName = androidModule?.getSelectedVariant()?.name ?: "debug"
        val resourceApk = androidModule?.let { resolveResourceApk(it) }
        val needsBuild = intermediateClasspaths.isEmpty()

        return ModuleContext(
            modulePath = module.path,
            variantName = variantName,
            compileClasspaths = compileClasspaths,
            intermediateClasspaths = intermediateClasspaths.toList(),
            runtimeDexFiles = runtimeDexFiles,
            resourceApk = resourceApk,
            needsBuild = needsBuild
        )
    }

    private fun resolveResourceApk(module: AndroidModule): File? {
        val variant = module.getSelectedVariant() ?: return null
        if (!variant.hasMainArtifact()) return null
        val artifact = variant.mainArtifact
        if (!artifact.hasAssembleTaskOutputListingFilePath()) return null

        val listing = resolveListingFile(File(artifact.assembleTaskOutputListingFilePath)) ?: return null
        return try {
            findApkInListing(listing)
        } catch (e: Exception) {
            null
        }
    }

    private fun findApkInListing(listing: File): File? {
        val elements = JSONObject(listing.readText()).optJSONArray("elements") ?: return null
        for (i in 0 until elements.length()) {
            val outputFile = elements.optJSONObject(i)?.optString("outputFile").orEmpty()
            if (!outputFile.endsWith(".apk")) continue
            val candidate = File(listing.parentFile, outputFile)
            if (candidate.exists()) return candidate
        }
        return null
    }

    private fun resolveListingFile(reference: File): File? {
        if (!reference.exists()) return null

        val text = reference.readText()
        if (text.trimStart().startsWith("{")) return reference
        if (!text.startsWith(REDIRECT_MARKER)) return null

        val target = Properties().apply { load(StringReader(text)) }
            .getProperty(REDIRECT_PROPERTY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val resolved = File(target).let { f ->
            if (f.isAbsolute) f else File(reference.parentFile, target).normalize()
        }
        return if (resolved.exists()) resolved else null
    }

    private const val REDIRECT_MARKER = "#- File Locator -"
    private const val REDIRECT_PROPERTY_NAME = "listingFile"
}

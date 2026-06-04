package com.itsaky.androidide.compose.preview.data.source

import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.io.StringReader
import java.util.Properties

data class ProjectContext(
    val modulePath: String?,
    val variantName: String,
    val compileClasspaths: List<File>,
    val intermediateClasspaths: Set<File>,
    val projectDexFiles: List<File>,
    val needsBuild: Boolean,
    val resourceApk: File? = null
)

class ProjectContextSource {

    fun resolveContext(filePath: String): ProjectContext {
        if (filePath.isBlank()) {
            LOG.info("Empty file path, returning default context")
            return ProjectContext(
                modulePath = null,
                variantName = "debug",
                compileClasspaths = emptyList(),
                intermediateClasspaths = emptySet(),
                projectDexFiles = emptyList(),
                needsBuild = false
            )
        }

        val file = File(filePath)
        LOG.info("Resolving project context for file: {}", file.absolutePath)

        val projectManager = IProjectManager.getInstance()
        val module = projectManager.findModuleForFile(file)

        if (module == null) {
            LOG.info("No module found for file")
            return ProjectContext(
                modulePath = null,
                variantName = "debug",
                compileClasspaths = emptyList(),
                intermediateClasspaths = emptySet(),
                projectDexFiles = emptyList(),
                needsBuild = false
            )
        }

        LOG.info("Found module: {} (type: {})", module.name, module.javaClass.simpleName)

        val intermediateClasspaths = module.getIntermediateClasspaths()
        val compileClasspaths = (module.getCompileClasspaths() + intermediateClasspaths).distinct()

        val projectDexFiles = module.getRuntimeDexFiles().toList()
        val androidModule = module as? AndroidModule
        val variantName = androidModule?.getSelectedVariant()?.name ?: "debug"
        val resourceApk = androidModule?.let { resolveResourceApk(it) }
        val needsBuild = intermediateClasspaths.isEmpty()

        LOG.info("Found {} total classpaths ({} compile, {} intermediate) for module: {}",
            compileClasspaths.size,
            compileClasspaths.size - intermediateClasspaths.size,
            intermediateClasspaths.size,
            module.name)
        LOG.info("Found {} project DEX files for runtime loading", projectDexFiles.size)
        LOG.info("Module path: {}, variant: {}, needsBuild: {}", module.path, variantName, needsBuild)

        if (!needsBuild) {
            intermediateClasspaths.forEach { cp ->
                LOG.info("  Intermediate: {} (exists: {})", cp.absolutePath, cp.exists())
            }
            projectDexFiles.forEach { dex ->
                LOG.info("  Project DEX: {} (exists: {})", dex.absolutePath, dex.exists())
            }
        }

        return ProjectContext(
            modulePath = module.path,
            variantName = variantName,
            compileClasspaths = compileClasspaths,
            intermediateClasspaths = intermediateClasspaths,
            projectDexFiles = projectDexFiles,
            needsBuild = needsBuild,
            resourceApk = resourceApk
        )
    }

    private fun resolveResourceApk(module: AndroidModule): File? {
        val variant = module.getSelectedVariant() ?: return null
        if (!variant.hasMainArtifact()) return null
        val artifact = variant.mainArtifact
        if (!artifact.hasAssembleTaskOutputListingFilePath()) return null

        val listing = resolveListingFile(File(artifact.assembleTaskOutputListingFilePath)) ?: return null

        return try {
            val elements = JSONObject(listing.readText()).optJSONArray("elements") ?: return null
            for (i in 0 until elements.length()) {
                val outputFile = elements.optJSONObject(i)?.optString("outputFile").orEmpty()
                if (outputFile.endsWith(".apk")) {
                    val candidate = File(listing.parentFile, outputFile)
                    if (candidate.exists()) {
                        return candidate
                    }
                }
            }
            LOG.warn("No APK entry found in output listing {}", listing.absolutePath)
            null
        } catch (e: Exception) {
            LOG.error("Failed to parse APK output listing {}", listing.absolutePath, e)
            null
        }
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

        val file = File(target)
        val resolved = if (file.isAbsolute) file else File(reference.parentFile, target).normalize()
        return if (resolved.exists()) resolved else null
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ProjectContextSource::class.java)
        private const val REDIRECT_MARKER = "#- File Locator -"
        private const val REDIRECT_PROPERTY_NAME = "listingFile"
    }
}

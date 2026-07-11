package com.itsaky.androidide.plugins

import com.android.build.api.variant.AndroidComponentsExtension
import com.itsaky.androidide.plugins.extension.AssetConfiguration
import com.itsaky.androidide.plugins.extension.AssetSource
import com.itsaky.androidide.plugins.extension.ExternalJarDependencyConfiguration
import com.itsaky.androidide.plugins.extension.JniLibAssetConfiguration
import com.itsaky.androidide.plugins.extension.RawAssetConfiguration
import com.itsaky.androidide.plugins.tasks.AddExternalAssetTask
import com.itsaky.androidide.plugins.util.DownloadUtils
import com.itsaky.androidide.plugins.util.capitalized
import com.itsaky.androidide.plugins.util.capitalizedName
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.newInstance
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

private fun Project.externalAssetsCacheDir(): Provider<Directory> =
    layout.buildDirectory.dir("externalAssetsCache")

abstract class ExternalAssetsExtension @Inject constructor(
    private val objects: ObjectFactory,
    private val project: Project
) {

    internal val assets: ExtensiblePolymorphicDomainObjectContainer<AssetConfiguration> =
        objects.polymorphicDomainObjectContainer(AssetConfiguration::class.java).apply {
            registerBinding(RawAssetConfiguration::class.java, RawAssetConfiguration::class.java)
            registerBinding(
                JniLibAssetConfiguration::class.java,
                JniLibAssetConfiguration::class.java
            )
            registerBinding(
                ExternalJarDependencyConfiguration::class.java,
                ExternalJarDependencyConfiguration::class.java
            )
        }

    /**
     * Add an external raw asset to be included in the APK.
     */
    fun rawAsset(name: String, configure: RawAssetConfiguration.() -> Unit) {
        assets.create(name, RawAssetConfiguration::class.java, configure)
    }

    /**
     * Add an external JNI library asset to be included in the APK.
     */
    fun jniLib(name: String, configure: JniLibAssetConfiguration.() -> Unit) {
        assets.create(name, JniLibAssetConfiguration::class.java, configure)
    }

    /**
     * Add an external JAR dependency to added to this module's dependencies.
     */
    fun jarDependency(name: String, configuration: ExternalJarDependencyConfiguration.() -> Unit) {
        val cacheDir = project.externalAssetsCacheDir().get().asFile
        cacheDir.mkdirs()
        val config = objects.newInstance<ExternalJarDependencyConfiguration>(name)
        config.configuration()

        require(config.source is AssetSource.External) {
            "External JAR dependency must have an external source."
        }

        val source = config.source as AssetSource.External
        DownloadUtils.downloadFile(
            source.url.toURL(),
            destination = cacheDir.resolve(config.jarName),
            sha256Checksum = source.sha256Checksum,
            logger = project.logger
        )

        val jarFileName = if (config.excludeEntryPrefixes.isEmpty()) {
            config.jarName
        } else {
            val original = cacheDir.resolve(config.jarName)
            val stripped = cacheDir.resolve("${config.jarName.removeSuffix(".jar")}-stripped.jar")
            stripJar(original, stripped, config.excludeEntryPrefixes, project.logger)
            stripped.name
        }

        val dep = project.dependencies.create(project.fileTree(cacheDir) {
            include(jarFileName)
        })

        project.dependencies {
            add(config.configuration, dep)
        }
    }
}

private fun stripJar(source: File, dest: File, excludePrefixes: List<String>, logger: Logger) {
    val prefixesFile = File(dest.parentFile, "${dest.name}.prefixes")
    val currentPrefixContent = excludePrefixes.sorted().joinToString("\n")
    val upToDate = dest.exists()
        && dest.lastModified() >= source.lastModified()
        && prefixesFile.exists()
        && prefixesFile.readText() == currentPrefixContent
    if (upToDate) {
        logger.lifecycle("Skipping strip of ${source.name}: stripped copy is up-to-date")
        return
    }
    logger.lifecycle("Stripping ${excludePrefixes.size} prefix(es) from ${source.name}…")
    val tmp = File(dest.parentFile, "${dest.name}.tmp")
    try {
        ZipInputStream(source.inputStream().buffered()).use { zin ->
            ZipOutputStream(tmp.outputStream().buffered()).use { zout ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    if (excludePrefixes.none { entry!!.name.startsWith(it) }) {
                        zout.putNextEntry(ZipEntry(entry.name))
                        zin.copyTo(zout)
                        zout.closeEntry()
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        prefixesFile.writeText(currentPrefixContent)
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
    val savedKb = (source.length() - dest.length()) / 1024
    logger.lifecycle("Stripped ${source.name}: saved ${savedKb} KB (${source.length()} → ${dest.length()} bytes)")
}

/**
 * Plugin used to download files from external sources and package them as assets.
 *
 * @author Akash Yadav
 */
class ExternalAssetsPlugin : Plugin<Project> {

    override fun apply(target: Project) = target.run {

        val objects = project.objects
        val externalAssets = objects.newInstance(ExternalAssetsExtension::class.java)
        extensions.add("externalAssets", externalAssets)

        val cacheDir = project.externalAssetsCacheDir()
        cacheDir.get().asFile.mkdirs()

        extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
            for ((name, asset) in externalAssets.assets.asMap) {
                val task = tasks.register(
                    "add${name.capitalized()}Asset${variant.capitalizedName()}",
                    AddExternalAssetTask::class.java
                ) {
                    this.asset.set(asset)
                    this.cacheDir.set(cacheDir)
                }

                // Add the asset if it's NOT download-only
                val source = when (asset) {
                    is RawAssetConfiguration -> variant.sources.assets
                    is JniLibAssetConfiguration -> variant.sources.jniLibs
                    else -> throw IllegalArgumentException("Unknown asset type: $asset")
                }

                source?.addGeneratedSourceDirectory(
                    taskProvider = task,
                    wiredWith = AddExternalAssetTask::outputDir
                )
            }
        }
    }
}

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
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.newInstance
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

        val dep = project.dependencies.create(project.fileTree(cacheDir) {
            include(config.jarName)
        })

        project.dependencies {
            add(config.configuration, dep)
        }
    }
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

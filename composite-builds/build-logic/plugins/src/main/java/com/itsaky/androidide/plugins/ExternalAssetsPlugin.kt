package com.itsaky.androidide.plugins

import com.android.build.api.variant.AndroidComponentsExtension
import com.itsaky.androidide.plugins.extension.AssetConfiguration
import com.itsaky.androidide.plugins.extension.JniLibAssetConfiguration
import com.itsaky.androidide.plugins.extension.RawAssetConfiguration
import com.itsaky.androidide.plugins.tasks.AddExternalAssetTask
import com.itsaky.androidide.plugins.util.capitalized
import com.itsaky.androidide.plugins.util.capitalizedName
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

abstract class ExternalAssetsExtension @Inject constructor(
    private val objects: ObjectFactory
) {

    internal val assets: ExtensiblePolymorphicDomainObjectContainer<AssetConfiguration> =
        objects.polymorphicDomainObjectContainer(AssetConfiguration::class.java).apply {
            registerBinding(RawAssetConfiguration::class.java, RawAssetConfiguration::class.java)
            registerBinding(
                JniLibAssetConfiguration::class.java,
                JniLibAssetConfiguration::class.java
            )
        }

    /**
     * Add an external raw asset to be included in the APK.
     */
    fun raw(name: String, configure: RawAssetConfiguration.() -> Unit) {
        assets.create(name, RawAssetConfiguration::class.java, configure)
    }

    /**
     * Add an external JNI library asset to be included in the APK.
     */
    fun jni(name: String, configure: JniLibAssetConfiguration.() -> Unit) {
        assets.create(name, JniLibAssetConfiguration::class.java, configure)
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

        val cacheDir = project.layout.buildDirectory.dir("externalAssetsCache")
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

                val source = when (asset) {
                    is RawAssetConfiguration -> variant.sources.assets
                    is JniLibAssetConfiguration -> variant.sources.jniLibs
                }

                source?.addGeneratedSourceDirectory(
                    taskProvider = task,
                    wiredWith = AddExternalAssetTask::outputDir
                )
            }
        }
    }
}

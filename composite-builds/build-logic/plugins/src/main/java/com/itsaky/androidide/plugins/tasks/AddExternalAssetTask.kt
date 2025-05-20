package com.itsaky.androidide.plugins.tasks

import com.itsaky.androidide.plugins.extension.AssetConfiguration
import com.itsaky.androidide.plugins.extension.AssetSource
import com.itsaky.androidide.plugins.extension.ExternalJarDependencyConfiguration
import com.itsaky.androidide.plugins.extension.JniLibAssetConfiguration
import com.itsaky.androidide.plugins.extension.RawAssetConfiguration
import com.itsaky.androidide.plugins.util.DownloadUtils
import com.itsaky.androidide.plugins.util.sha256
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * @author Akash Yadav
 */
abstract class AddExternalAssetTask : DefaultTask() {

    /**
     * The asset to be added.
     */
    @get:Input
    abstract val asset: Property<AssetConfiguration>

    /**
     * The directory where the asset should be cached.
     */
    @get:InputDirectory
    abstract val cacheDir: DirectoryProperty

    /**
     * The directory where the asset should be added. Optional only for JAR dependency assets.
     */
    @get:OutputDirectory
    @get:Optional
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun addAsset() {
        val asset = asset.get()
        val cacheDir = cacheDir.asFile.get().also { it.mkdirs() }
        val cachedFile = asset.fetchSourceInto(cacheDir)

        if (asset is ExternalJarDependencyConfiguration) {
            // no need to process further
            return
        }

        val outputDir = outputDir.asFile.get()
        val destFile = asset.resolveDestFile(outputDir)
        val destDir = destFile.parentFile!!
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("Unable to create directory $destDir")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        if (destFile.exists()) {
            if (digest.sha256(cachedFile) != digest.sha256(destFile)) {
                logger.quiet("Checksum mismatch. Deleting {}.", destFile)
                destFile.delete()
            } else {
                logger.quiet("Asset {} already exists.", destFile)
                return
            }
        }

        cachedFile.copyTo(destFile)
    }

    private fun AssetConfiguration.fetchSourceInto(
        cacheDir: File,
    ): File = when (this.source) {
        is AssetSource.External -> fetchExternalSourceInto(cacheDir)
    }

    private fun AssetConfiguration.fetchExternalSourceInto(
        cacheDir: File
    ): File {
        val source = this.source as AssetSource.External
        val dest = this.resolveCacheFile(cacheDir)
        val destDir = dest.parentFile!!
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("Unable to create directory $destDir")
        }

        DownloadUtils.downloadFile(
            url = source.url.toURL(),
            destination = dest,
            sha256Checksum = source.sha256Checksum,
            logger = project.logger
        )

        return dest
    }
}

private fun AssetConfiguration.resolveDestFile(outputDir: File): File = when(this) {
    is RawAssetConfiguration -> outputDir.resolve(this.assetPath)
    is JniLibAssetConfiguration -> outputDir.resolve("${this.abi.abi}/lib${this.libName}.so")
    is ExternalJarDependencyConfiguration -> outputDir.resolve(this.jarName)
}

private fun AssetConfiguration.resolveCacheFile(cacheDir: File): File = when (this) {
    is RawAssetConfiguration -> cacheDir.resolve(this.assetPath)
    is JniLibAssetConfiguration -> cacheDir.resolve("lib${this.libName}_${this.abi.abi}.so")
    is ExternalJarDependencyConfiguration -> cacheDir.resolve(this.jarName)
}

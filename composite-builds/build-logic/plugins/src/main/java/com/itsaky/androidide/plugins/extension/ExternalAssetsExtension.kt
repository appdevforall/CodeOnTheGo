package com.itsaky.androidide.plugins.extension

import org.gradle.api.Named
import java.io.Serializable
import java.net.URI
import javax.inject.Inject

/**
 * Extension for the `externalAssets` configuration.
 */
interface ExternalAssetsExtension {

    /**
     * Add an external raw asset to be included in the APK.
     *
     * @param name The name of the asset.
     */
    fun raw(name: String, configure: RawAssetConfiguration.() -> Unit)

    /**
     * Add an external JNI library asset to be included in the APK.
     */
    fun jniLib(name: String, configure: JniLibAssetConfiguration.() -> Unit)
}

/**
 * The CPU architecture of a JNI library.ABI of a JNI library.
 */
enum class JniLibAbi(
    val abi: String
) {
    Aarch64("arm64-v8a"),
    Arm("armeabi-v7a"),
    X86("x86"),
    X86_64("x86_64")
}

/**
 * Source of an asset to be included in the APK.
 */
sealed interface AssetSource {

    /**
     * An external asset, to be downloaded from the internet.
     *
     * @property url The URL to download the asset from.
     * @property sha256Checksum The expected SHA-256 checksum of the asset. If the downloaded asset
     *                          does not have this checksum, the build will fail.
     */
    data class External(
        val url: URI,
        val sha256Checksum: String
    ) : AssetSource, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * Configuration for a single asset to be included in the APK.
 */
sealed class AssetConfiguration (
    private val __name: String
): Named, Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * The source of the asset.
     */
    abstract var source: AssetSource

    override fun getName(): String = __name
}

/**
 * Configuration for a single asset to be included in the APK.
 */
abstract class RawAssetConfiguration @Inject constructor(
    name: String
) : AssetConfiguration(name) {

    /**
     * The path to the asset relative to the `assets` directory in the APK.
     */
    abstract var assetPath: String
}

/**
 * Configuration for a single JNI library asset to be included in the APK.
 */
abstract class JniLibAssetConfiguration @Inject constructor(
    name: String
) : AssetConfiguration(name) {

    /**
     * The name of the shared library, without the `lib` prefix or the `.so` file extension.
     */
    abstract var libName: String

    /**
     * The ABI of the JNI library.
     */
    abstract var abi: JniLibAbi
}

/**
 * Configuration for a single JAR dependency to added to the declaring module's dependencies.
 */
abstract class ExternalJarDependencyConfiguration @Inject constructor(
    name: String
): AssetConfiguration(name) {

    /**
     * The configuration of the JAR dependency.
     */
    var configuration: String = "implementation"

    /**
     * The name of the JAR file to be added to the dependencies.
     */
    var jarName: String = "${name}.jar"
}
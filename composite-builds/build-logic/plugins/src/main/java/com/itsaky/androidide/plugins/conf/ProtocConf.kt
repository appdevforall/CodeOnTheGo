package com.itsaky.androidide.plugins.conf

import com.google.protobuf.gradle.ProtobufExtension
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.ShellUtils
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File

fun Project.configureProtoc(
	protobuf: ProtobufExtension,
	protocVersion: Provider<String>,
) {
	if (configureOnDeviceProtoc(protobuf)) return

	configureProtocArtifact(protobuf, protocVersion)
}

private fun Project.configureOnDeviceProtoc(protobuf: ProtobufExtension): Boolean {
	if (isTermuxAppPackageNameSet() || isTermuxJdk()) {
		// this is an on-device build
		// find path to the protoc binary

		val protocPath = ShellUtils.which("protoc")
		if (protocPath == null) {
			logger.warn(
				"Unable to get path to protoc binary for on-device build." +
					" Falling back to using maven artifact, which is likely to fail.",
			)
			return false
		}

		val protoc = File(protocPath)
		if (!protoc.exists()) {
			logger.warn(
				"protoc path $protocPath does not exist." +
					" Falling back to using maven artifact",
			)
			return false
		}

		if (!protoc.canExecute()) {
			logger.warn(
				"protoc path $protocPath is not executable." +
					" Falling back to using maven artifact",
			)
			return false
		}

		logger.lifecycle("Using protoc from $protocPath")
		protobuf.protoc {
			path = protoc.absolutePath
		}

		return true
	}

	return false
}

fun Project.configureProtocArtifact(
	protobuf: ProtobufExtension,
	protocVersion: Provider<String>,
) {
	val protocModule = "com.google.protobuf:protoc:${protocVersion.get()}"
	logger.lifecycle("Using protoc module: $protocModule")

	protobuf.protoc {
		artifact = protocModule
	}
}

fun isTermuxAppPackageNameSet() = System.getenv("TERMUX_APP__PACKAGE_NAME") == BuildConfig.PACKAGE_NAME

fun isTermuxJdk() =
	System.getProperty("java.vendor") == "Termux" ||
		System.getProperty("java.vm.vendor") == "Termux"

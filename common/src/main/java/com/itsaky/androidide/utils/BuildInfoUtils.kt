package com.itsaky.androidide.utils

import com.itsaky.androidide.buildinfo.BuildInfo

/**
 * Provides various information about the IDE build.
 *
 * @author Akash Yadav
 */
object BasicBuildInfo {

	/**
	 * Basic info, includes internal app name and version name.
	 *
	 * Not a `const val`: [BuildInfo.VERSION_NAME_SIMPLE] changes between builds, and a
	 * `const val` would inline it here and put it back in this module's ABI. See ADR 0012.
	 */
	@JvmField
	val BASIC_INFO = "${BuildInfo.INTERNAL_NAME} (${BuildInfo.VERSION_NAME_SIMPLE})"

	val hasReleaseVersion: Boolean
		get() = BuildInfo.RELEASE_VERSION.isNotBlank()

	fun formatVersion(
		releaseVersion: String = BuildInfo.RELEASE_VERSION,
		simpleVersion: String = BuildInfo.VERSION_NAME_SIMPLE,
	): String = if (releaseVersion.isNotBlank()) "$releaseVersion ($simpleVersion)" else simpleVersion

	@JvmStatic
	@JvmOverloads
	fun shareableBuildInfo(
		internalName: String = BuildInfo.INTERNAL_NAME,
		releaseVersion: String = BuildInfo.RELEASE_VERSION,
		simpleVersion: String = BuildInfo.VERSION_NAME_SIMPLE,
	): String = "$internalName ${formatVersion(releaseVersion, simpleVersion)}"
}

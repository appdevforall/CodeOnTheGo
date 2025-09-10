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
	 */
	const val BASIC_INFO = "${BuildInfo.INTERNAL_NAME} (${BuildInfo.VERSION_NAME})"
}
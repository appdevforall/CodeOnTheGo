package com.itsaky.androidide.assets

import java.io.File
import java.io.FileNotFoundException

class MissingAssetsEntryException(
	cause: Throwable,
) : Exception(cause)

/**
 * The split assets zip that debug builds install from is not on the device. Extends
 * [FileNotFoundException] so it keeps the missing-asset handling (no GlitchTip report), while
 * letting the UI tell the user exactly which file to copy where.
 */
class MissingSplitAssetsZipException(
	val zipFile: File,
) : FileNotFoundException("Assets zip file not found at path: ${zipFile.path}")

package com.itsaky.androidide.activities.editor

import com.itsaky.androidide.models.SaveResult

/**
 * Folds one saved file into [result]'s flags.
 *
 * `resourceXmlSaved` is what the post-save `generateSources()` call sites gate on - see the
 * rationale on [SaveResult.resourceXmlSaved]. [isAndroidResource] is consulted only for a
 * modified XML file whose flag is still unset, so callers can pass the project-manager lookup
 * without paying for it on every save.
 */
internal fun accumulateSaveFlags(
	result: SaveResult,
	fileName: String,
	modified: Boolean,
	isAndroidResource: () -> Boolean,
) {
	if (!result.gradleSaved) {
		result.gradleSaved =
			modified && (fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts"))
	}

	val isXml = fileName.endsWith(".xml")
	if (!result.xmlSaved) {
		result.xmlSaved = modified && isXml
	}

	if (!result.resourceXmlSaved) {
		result.resourceXmlSaved = modified && isXml && isAndroidResource()
	}
}

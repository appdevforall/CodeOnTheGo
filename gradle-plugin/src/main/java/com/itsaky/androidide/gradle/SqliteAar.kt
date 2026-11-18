package com.itsaky.androidide.gradle

import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * The AAR that [property] names, for a plugin applied only when `cogo.sqliteDebugger=true`.
 * Fails the build when the property is unset or the file is missing: the project opted in, and a
 * build that quietly drops the feature leaves the user wondering why it does nothing.
 *
 * @param aarLabel what the AAR is called in the "not found" message.
 * @param feature what the user's opt-in turns on, for the "remove the line" advice.
 */
internal fun Project.requireSqliteAar(
	property: String,
	aarLabel: String,
	feature: String,
): File {
	val aar =
		findProperty(property)?.let { File(it.toString()) }
			?: throw GradleException(
				"'$PROPERTY_SQLITE_DEBUGGER_ENABLED' is true but no property '$property' is set. " +
					"Remove the line from gradle.properties to build without the $feature.",
			)
	if (!aar.isFile) {
		throw GradleException(
			"$aarLabel not found at '${aar.absolutePath}'. " +
				"Remove '$PROPERTY_SQLITE_DEBUGGER_ENABLED' from gradle.properties to build without it.",
		)
	}
	return aar
}

package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType

const val JAVA_STRATUM = "Java"
const val KOTLIN_STRATUM = "Kotlin"

val ReferenceType.isKotlinType: Boolean
	get() =
		try {
			availableStrata().contains(KOTLIN_STRATUM)
		} catch (err: AbsentInformationException) {
			false
		}

fun ReferenceType.sourcePathOrNull(): String? =
	try {
		sourcePaths(JAVA_STRATUM).firstOrNull()
	} catch (err: AbsentInformationException) {
		null
	}

fun Location.sourcePathOrNull(): String? =
	try {
		sourcePath(JAVA_STRATUM)
	} catch (err: AbsentInformationException) {
		null
	}

fun Location.sourceNameOrNull(): String? =
	try {
		sourceName(JAVA_STRATUM)
	} catch (err: AbsentInformationException) {
		null
	}

fun Location.lineNumberInSource(): Int = lineNumber(JAVA_STRATUM)

fun matchesSourcePath(
	breakpointPath: String,
	stratumSourcePath: String?,
): Boolean = stratumSourcePath != null && breakpointPath.endsWith(stratumSourcePath)

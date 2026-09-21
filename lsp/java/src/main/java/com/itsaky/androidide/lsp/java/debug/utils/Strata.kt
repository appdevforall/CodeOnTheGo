package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ObjectCollectedException
import com.sun.jdi.ReferenceType

const val JAVA_STRATUM = "Java"
const val KOTLIN_STRATUM = "Kotlin"

val ReferenceType.isKotlinType: Boolean
	get() =
		try {
			availableStrata().contains(KOTLIN_STRATUM)
		} catch (err: AbsentInformationException) {
			false
		} catch (err: ObjectCollectedException) {
			false
		}

/**
 * This type's source path, or null when it has no debug info or has been unloaded.
 *
 * [ObjectCollectedException] is caught alongside the absent-information case because a candidate
 * can be unloaded between `classesByName` and this call. Letting it escape would abort the whole
 * candidate loop rather than skipping the one dead type, and a Kotlin file supplies many candidates.
 */
fun ReferenceType.sourcePathOrNull(): String? =
	try {
		sourcePaths(JAVA_STRATUM).firstOrNull()
	} catch (err: AbsentInformationException) {
		null
	} catch (err: ObjectCollectedException) {
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

/**
 * This location read in the Kotlin stratum, which is the only one with a multi-file table.
 *
 * The Java stratum has one `SourceFile` per class, so a body inlined from another file is reported
 * under the caller's file name at a synthetic line past its end. The Kotlin stratum names the file
 * the code was written in. The two agree outside inlined code.
 *
 * Placement must stay in the Java stratum ([locationsOfLineInSource]), so these are for display
 * only: a position reported here does not round-trip to a breakpoint.
 *
 * JDI falls back to the declaring type's default stratum when [KOTLIN_STRATUM] is absent, so these
 * are safe on a Java class.
 */
fun Location.lineNumberInKotlin(): Int = lineNumber(KOTLIN_STRATUM)

fun Location.sourceNameInKotlinOrNull(): String? =
	try {
		sourceName(KOTLIN_STRATUM)
	} catch (err: AbsentInformationException) {
		null
	}

/**
 * Locations for [line] read in the same stratum [lineNumberInSource] reports in.
 *
 * The no-argument `locationsOfLine` resolves through the VM's default stratum, which is null here,
 * so JDI falls back to the type's own default - Kotlin for a Kotlin class. A breakpoint would then
 * be placed by Kotlin-stratum line and reported by Java-stratum line, and the two only agree
 * outside inlined code.
 */
fun ReferenceType.locationsOfLineInSource(line: Int): List<Location> = locationsOfLine(JAVA_STRATUM, null, line)

fun matchesSourcePath(
	breakpointPath: String,
	stratumSourcePath: String?,
): Boolean = stratumSourcePath != null && breakpointPath.endsWith(stratumSourcePath)

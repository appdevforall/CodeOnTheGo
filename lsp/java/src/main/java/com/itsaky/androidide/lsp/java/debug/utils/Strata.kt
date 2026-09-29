package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ObjectCollectedException
import com.sun.jdi.ReferenceType
import org.jetbrains.kotlin.codegen.inline.KOTLIN_DEBUG_STRATA_NAME
import org.jetbrains.kotlin.codegen.inline.KOTLIN_STRATA_NAME
import org.jetbrains.kotlin.codegen.inline.SourceMapper

const val JAVA_STRATUM = "Java"

/** The compiler's placeholder file for generated code, which belongs to no call site. */
private val FAKE_SOURCE_NAME = SourceMapper.FAKE_FILE_NAME

val ReferenceType.isKotlinType: Boolean
	get() =
		try {
			availableStrata().contains(KOTLIN_STRATA_NAME)
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
 * JDI falls back to the declaring type's default stratum when the Kotlin stratum is absent, so these
 * are safe on a Java class. A code index the stratum does not map reads back as -1, which would
 * render as `:-1`, so the Java-stratum line stands in for it.
 */
fun Location.lineNumberInKotlin(): Int = lineNumber(KOTLIN_STRATA_NAME).takeIf { it > 0 } ?: lineNumberInSource()

fun Location.sourceNameInKotlinOrNull(): String? =
	try {
		sourceName(KOTLIN_STRATA_NAME)
	} catch (err: AbsentInformationException) {
		null
	}

/**
 * The line of this class's own source that produced this location, or null when none did.
 *
 * `KotlinDebug` maps an inlined body's synthetic output lines back to the call site the user wrote,
 * so it is the one stratum that names a position the editor can open and a breakpoint can bind to.
 * Its line table covers those lines and nothing else: `SMAPBuilder` builds it solely from mappings
 * carrying a call site.
 *
 * JDI answers an unmapped code index by best match rather than by failing. `ConcreteMethodImpl`
 * drops the lines a stratum does not map, then `codeIndexToLineInfo` returns the nearest preceding
 * line it kept, so in a method that inlines anywhere a line outside every inlined body reads back as
 * some unrelated call site. Gating on [isInlinedBody] rather than on a non-positive result is what
 * keeps that out; a method with no inlining at all is the only case that does return -1.
 */
fun Location.inlineCallSiteLineOrNull(): Int? {
	if (!isInlinedBody()) {
		return null
	}

	return try {
		lineNumber(KOTLIN_DEBUG_STRATA_NAME).takeIf { it > 0 }
	} catch (err: AbsentInformationException) {
		null
	}
}

/**
 * Whether this location is code inlined into the declaring class from elsewhere.
 *
 * The Kotlin stratum maps the class's own lines to themselves and writes everything inlined into it
 * past the end of that range, so the two strata agree exactly off inlined code. `fake.kt` also reads
 * as a foreign file but is generated code rather than an inlining, so it has no call site to find.
 */
private fun Location.isInlinedBody(): Boolean =
	isInlinedBody(
		kotlinSourceName = sourceNameInKotlinOrNull(),
		javaSourceName = sourceNameOrNull(),
		kotlinLine = lineNumberInKotlin(),
		javaLine = lineNumberInSource(),
	)

/**
 * [isInlinedBody] over the four values it reads, so the decision can be tested without a live VM.
 *
 * Comparing the line as well as the name is what catches an inline function declared in the same
 * file as its caller: the Kotlin stratum then answers the same file name and only the line differs.
 */
fun isInlinedBody(
	kotlinSourceName: String?,
	javaSourceName: String?,
	kotlinLine: Int,
	javaLine: Int,
): Boolean {
	if (kotlinSourceName == null || kotlinSourceName == FAKE_SOURCE_NAME) {
		return false
	}

	return kotlinSourceName != javaSourceName || kotlinLine != javaLine
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

package org.appdevforall.cotg.corpus.mixedlang.core

/** Kotlin class called from Java (JavaPresenter) - the direction the daemon already
 * supported (Java compiles after Kotlin, with Kotlin's classesDir on its classpath). */
class KotlinFormatter {

	fun formatLabel(value: Int): String = "Formatted: $value (QB_FORMAT_MARKER_V2)"
}

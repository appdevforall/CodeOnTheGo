package org.appdevforall.cotg.corpus.composekotlin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Runtime-level composables (state, remember, recomposition scopes) -- the part of
 * Compose the compiler plugin transforms. UI emitters are irrelevant to the compile
 * pipeline under test, and their AARs are deliberately not on the corpus classpath.
 */
@Composable
fun GreetingScreen(name: String) {
	var taps by remember { mutableIntStateOf(0) }
	GreetingLine("A composable rendered greeting for $name.", taps)
	taps += 1
}

@Composable
fun GreetingLine(
	text: String,
	taps: Int,
) {
	GreetingLog.record("$text [taps=$taps]")
}

/** Plain sink the composables write into; keeps the composables observable. */
object GreetingLog {
	val lines = mutableListOf<String>()

	fun record(line: String) {
		lines += line
	}
}

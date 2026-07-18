package org.appdevforall.cotg.corpus.composekotlin

/** Pure logic bystander: must NOT recompile when only Composables.kt changes. */
object GreetingFormatter {
	fun format(name: String): String = "Hello, $name! Composables live in Composables.kt."
}

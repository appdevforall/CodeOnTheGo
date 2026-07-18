package org.appdevforall.cotg.corpus.hellokotlin

/** Pure logic: builds the greeting shown on the main screen. No Android imports. */
class Greeter {
	fun greet(name: String): String = "Hello, $name! Welcome to the corpus app."
}

package org.appdevforall.cotg.corpus.medium.core

/** Produces a greeting for a named user; implementations vary by tone. */
interface Greeter {
	fun greet(name: String): String
}

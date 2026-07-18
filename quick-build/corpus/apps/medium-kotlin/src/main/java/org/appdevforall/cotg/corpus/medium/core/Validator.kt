package org.appdevforall.cotg.corpus.medium.core

/** Validates a piece of user input against a rule. */
interface Validator {
	fun isValid(input: String): Boolean
}

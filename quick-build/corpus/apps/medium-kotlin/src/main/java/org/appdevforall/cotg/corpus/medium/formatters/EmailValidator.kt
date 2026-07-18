package org.appdevforall.cotg.corpus.medium.formatters

import org.appdevforall.cotg.corpus.medium.core.Validator

class EmailValidator : Validator {
	override fun isValid(input: String): Boolean = input.contains("@") && input.contains(".")
}

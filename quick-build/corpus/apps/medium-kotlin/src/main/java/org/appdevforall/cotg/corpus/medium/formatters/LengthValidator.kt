package org.appdevforall.cotg.corpus.medium.formatters

import org.appdevforall.cotg.corpus.medium.core.Validator

class LengthValidator : Validator {
	override fun isValid(input: String): Boolean = input.length in 1..64
}

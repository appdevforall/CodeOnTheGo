package org.appdevforall.cotg.corpus.medium.formatters

import org.appdevforall.cotg.corpus.medium.core.Greeter

class CasualGreeter : Greeter {
	override fun greet(name: String): String = "Yo $name!"
}

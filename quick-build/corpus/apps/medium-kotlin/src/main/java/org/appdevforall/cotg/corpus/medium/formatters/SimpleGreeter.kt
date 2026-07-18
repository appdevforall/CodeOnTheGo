package org.appdevforall.cotg.corpus.medium.formatters

import org.appdevforall.cotg.corpus.medium.core.Greeter

class SimpleGreeter : Greeter {
	override fun greet(name: String): String = "Hi, $name."
}

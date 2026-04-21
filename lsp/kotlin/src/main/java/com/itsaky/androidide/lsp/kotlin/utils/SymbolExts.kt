package com.itsaky.androidide.lsp.kotlin.utils

import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingDeclaration
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol

@OptIn(KaContextParameterApi::class)
context(session: KaSession)
val KaSymbol.containingTopLevelClassDeclaration: KaClassSymbol?
	get() {
		var current: KaSymbol? = this

		var lastClass: KaClassSymbol? = null

		while (current != null) {
			if (current is KaClassSymbol) {
				lastClass = current
			}
			current = current.containingDeclaration
		}

		return lastClass
	}
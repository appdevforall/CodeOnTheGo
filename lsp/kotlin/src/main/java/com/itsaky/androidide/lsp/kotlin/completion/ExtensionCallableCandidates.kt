package com.itsaky.androidide.lsp.kotlin.completion

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.Name

/** The extensions in [scope] whose names pass [nameFilter] and that can be called on [receiverType]. */
internal fun KaSession.extensionCallableCandidates(
	receiverType: KaType,
	scope: KaScope,
	nameFilter: (Name) -> Boolean,
): Sequence<KaCallableSymbol> =
	scope
		.callables(nameFilter)
		.filter { symbol ->
			if (!symbol.isExtension) return@filter false

			val extReceiverType = symbol.receiverType ?: return@filter false
			receiverType.isSubtypeOf(extReceiverType)
		}

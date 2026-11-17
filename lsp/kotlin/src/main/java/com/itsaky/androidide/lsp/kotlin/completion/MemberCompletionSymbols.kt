package com.itsaky.androidide.lsp.kotlin.completion

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression

/**
 * The symbols member completion offers after `receiver.`, before they become completion items.
 *
 * @property members Callables and classifiers, in the order they are offered.
 * @property extensionReceiverType The type whose applicable extensions are offered as well, or
 *   `null` when no extension can be called on the receiver.
 */
internal class MemberCompletionSymbols(
	val members: List<KaSymbol>,
	val extensionReceiverType: KaType?,
)

/**
 * The members of [receiver] whose names pass [nameFilter], or `null` when the receiver has no type.
 *
 * With [isSafeCall] (`receiver?.`) the members of the receiver's non-null type are offered as well.
 */
internal fun KaSession.memberCompletionSymbols(
	receiver: KtExpression,
	isSafeCall: Boolean,
	nameFilter: (Name) -> Boolean,
): MemberCompletionSymbols? {
	val receiverType = receiver.expressionType ?: return null
	val members = mutableListOf<KaSymbol>()
	addTypeMembers(receiverType, nameFilter, members)
	if (isSafeCall) {
		addTypeMembers(receiverType.withNullability(isMarkedNullable = false), nameFilter, members)
	}
	return MemberCompletionSymbols(members, receiverType)
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.addTypeMembers(
	type: KaType,
	nameFilter: (Name) -> Boolean,
	members: MutableList<KaSymbol>,
) {
	val typeScope = type.scope
	if (typeScope != null) {
		members += typeScope.getCallableSignatures(nameFilter).map { it.symbol }
		members += typeScope.getClassifierSymbols(nameFilter)
		return
	}

	// fallback approach when typeScope is not available
	val classSymbol = (type as? KaClassType)?.symbol as? KaClassSymbol ?: return
	val memberScope = classSymbol.memberScope
	members += memberScope.callables(nameFilter)
	members += memberScope.classifiers(nameFilter)
}

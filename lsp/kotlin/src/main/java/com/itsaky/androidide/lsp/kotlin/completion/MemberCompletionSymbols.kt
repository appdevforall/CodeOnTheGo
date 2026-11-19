package com.itsaky.androidide.lsp.kotlin.completion

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

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
	qualifierClass(receiver)?.let { return classQualifierSymbols(it, nameFilter) }

	val receiverType = receiver.expressionType ?: return null
	val members = mutableListOf<KaSymbol>()
	addTypeMembers(receiverType, nameFilter, members)
	if (isSafeCall) {
		addTypeMembers(receiverType.withNullability(isMarkedNullable = false), nameFilter, members)
	}
	return MemberCompletionSymbols(members, receiverType)
}

/**
 * The class [receiver] names when it is used as a qualifier (`Collections.`, `Color.`) rather than as
 * a value. Such a reference has no value type - the Analysis API reports `kotlin.Unit` for it - so its
 * members come from the class's static scope instead. An object is a value and is excluded.
 */
private fun KaSession.qualifierClass(receiver: KtExpression): KaClassSymbol? {
	val reference =
		(if (receiver is KtQualifiedExpression) receiver.selectorExpression else receiver) as? KtNameReferenceExpression
			?: return null
	val classSymbol = reference.mainReference.resolveToSymbol() as? KaClassSymbol ?: return null
	return classSymbol.takeUnless {
		it.classKind == KaClassKind.OBJECT || it.classKind == KaClassKind.COMPANION_OBJECT
	}
}

/** Static members and nested classifiers of [classSymbol], plus its companion's members if it has one. */
private fun KaSession.classQualifierSymbols(
	classSymbol: KaClassSymbol,
	nameFilter: (Name) -> Boolean,
): MemberCompletionSymbols {
	val staticScope = classSymbol.staticMemberScope
	val members = mutableListOf<KaSymbol>()
	members += staticScope.callables(nameFilter)
	members += staticScope.classifiers(nameFilter)

	val companion =
		(classSymbol as? KaNamedClassSymbol)?.companionObject
			?: return MemberCompletionSymbols(members, extensionReceiverType = null)
	val companionType = buildClassType(companion)
	addTypeMembers(companionType, nameFilter, members)
	return MemberCompletionSymbols(members, extensionReceiverType = companionType)
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

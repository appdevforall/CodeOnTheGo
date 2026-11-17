package com.itsaky.androidide.lsp.kotlin.completion

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaScopeContext
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * The extensions that can be called on [receiverType] from [scopeContext], whose names pass
 * [nameFilter].
 *
 * Package and importing scopes are looked up by name, never enumerated: right after `.` every name
 * passes, and enumerating them makes the Analysis API materialize every callable the file can see,
 * parsing Java sources on the way - most of a member completion's time on device. The names come from
 * [indexedExtensionNames], given the JVM symbol index spellings of the receiver type and all its
 * supertypes ([extensionIndexReceiverNames]).
 *
 * The implicit receivers' type scopes are looked up by name too, with [memberExtensionNames]: inside
 * an Android class they hold every member of its whole framework hierarchy, all materialized just to
 * keep the few member extensions. The remaining scopes - local, type parameter, static member and
 * script - are small and the index never sees them, so they are enumerated.
 */
internal fun KaSession.extensionCallableCandidates(
	receiverType: KaType,
	scopeContext: KaScopeContext,
	indexedExtensionNames: (receiverTypeNames: Set<String>) -> Set<Name>,
	nameFilter: (Name) -> Boolean,
): Sequence<KaCallableSymbol> {
	val enumerated = scopeContext.compositeScope { it.isEnumerated() }.callables(nameFilter)
	val members = callablesNamed(scopeContext, memberExtensionNames(scopeContext).filter(nameFilter)) { it is KaScopeKind.TypeScope }
	val indexedNames = indexedExtensionNames(receiverTypeIndexNames(receiverType)).filter(nameFilter)
	val indexed = callablesNamed(scopeContext, indexedNames) { !it.isEnumerated() && it !is KaScopeKind.TypeScope }
	return (enumerated + members + indexed).filter { symbol ->
		if (!symbol.isExtension) return@filter false

		val extReceiverType = symbol.receiverType ?: return@filter false
		receiverType.isSubtypeOf(extReceiverType)
	}
}

/**
 * The spellings under which the JVM symbol index may store [classId] as an extension receiver.
 *
 * Library rows come from Kotlin metadata, which separates nested classes with `.`; source rows come
 * from `SourceFileIndexer`, which uses `$`. Both are the same for a top-level class.
 */
internal fun extensionIndexReceiverNames(classId: ClassId): Set<String> {
	val packagePath = classId.packageFqName.asString().replace('.', '/')
	val relativeName = classId.relativeClassName.asString()

	fun qualified(name: String) = if (packagePath.isEmpty()) name else "$packagePath/$name"

	return linkedSetOf(qualified(relativeName.replace('.', '$')), qualified(relativeName))
}

/**
 * Names of the extensions declared as members of the implicit receivers' classes or their
 * supertypes, read from declared members only. Java classes are skipped: they cannot declare member
 * extensions, and in an Android class they are nearly the whole hierarchy.
 */
internal fun KaSession.memberExtensionNames(scopeContext: KaScopeContext): Set<Name> {
	val names = mutableSetOf<Name>()
	val visited = mutableSetOf<KaClassSymbol>()
	for (receiver in scopeContext.implicitReceivers) {
		for (type in sequenceOf(receiver.type) + receiver.type.allSupertypes) {
			val classSymbol = (type.lowerBoundIfFlexible() as? KaClassType)?.symbol as? KaClassSymbol ?: continue
			if (classSymbol.origin == KaSymbolOrigin.JAVA_SOURCE || classSymbol.origin == KaSymbolOrigin.JAVA_LIBRARY) continue
			if (!visited.add(classSymbol)) continue
			classSymbol.declaredMemberScope
				.callables { true }
				.filter { it.isExtension }
				.mapNotNullTo(names) { it.callableId?.callableName }
		}
	}
	return names
}

private fun KaSession.callablesNamed(
	scopeContext: KaScopeContext,
	names: List<Name>,
	scopeKinds: (KaScopeKind) -> Boolean,
): Sequence<KaCallableSymbol> = if (names.isEmpty()) emptySequence() else scopeContext.compositeScope(scopeKinds).callables(names)

private fun KaScopeKind.isEnumerated(): Boolean =
	this is KaScopeKind.LocalScope ||
		this is KaScopeKind.TypeParameterScope ||
		this is KaScopeKind.StaticMemberScope ||
		this is KaScopeKind.ScriptMemberScope

private fun KaType.lowerBoundIfFlexible(): KaType = if (this is KaFlexibleType) lowerBound else this

private fun KaSession.receiverTypeIndexNames(receiverType: KaType): Set<String> =
	(sequenceOf(receiverType) + receiverType.allSupertypes)
		.mapNotNull { type -> type.lowerBoundIfFlexible() as? KaClassType }
		.flatMapTo(LinkedHashSet()) { extensionIndexReceiverNames(it.classId) }

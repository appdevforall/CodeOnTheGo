package com.itsaky.androidide.lsp.kotlin.completion

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaScopeContext
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
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
 * supertypes ([extensionIndexReceiverNames]). Scopes the index never sees - local, type, type
 * parameter, static member and script scopes - are small and are enumerated.
 */
internal fun KaSession.extensionCallableCandidates(
	receiverType: KaType,
	scopeContext: KaScopeContext,
	indexedExtensionNames: (receiverTypeNames: Set<String>) -> Set<Name>,
	nameFilter: (Name) -> Boolean,
): Sequence<KaCallableSymbol> {
	val unindexed = scopeContext.compositeScope { it.isUnindexed() }.callables(nameFilter)
	val names = indexedExtensionNames(receiverTypeIndexNames(receiverType)).filter(nameFilter)
	val indexed =
		if (names.isEmpty()) {
			emptySequence()
		} else {
			scopeContext.compositeScope { !it.isUnindexed() }.callables(names)
		}
	return (unindexed + indexed).filter { symbol ->
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

private fun KaScopeKind.isUnindexed(): Boolean =
	this is KaScopeKind.LocalScope ||
		this is KaScopeKind.TypeScope ||
		this is KaScopeKind.TypeParameterScope ||
		this is KaScopeKind.StaticMemberScope ||
		this is KaScopeKind.ScriptMemberScope

private fun KaSession.receiverTypeIndexNames(receiverType: KaType): Set<String> =
	(sequenceOf(receiverType) + receiverType.allSupertypes)
		.mapNotNull { type -> (if (type is KaFlexibleType) type.lowerBound else type) as? KaClassType }
		.flatMapTo(LinkedHashSet()) { extensionIndexReceiverNames(it.classId) }

package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/*
 * Declaration lookups that read only KtFile.declarations instead of walking the whole PSI tree.
 * Local declarations have no ClassId and are never top-level, so they never match.
 *
 * The returned sequences are lazy and touch PSI while iterated, so callers must fully consume
 * them under the project read lock. The name sequences may contain duplicates.
 */

internal fun Sequence<KtFile>.classesWithId(classId: ClassId): Sequence<KtClassOrObject> =
	declarationsAndNested()
		.filterIsInstance<KtClassOrObject>()
		.filter { it.getClassId() == classId }

internal fun Sequence<KtFile>.typeAliasesWithId(classId: ClassId): Sequence<KtTypeAlias> =
	declarationsAndNested()
		.filterIsInstance<KtTypeAlias>()
		.filter { it.getClassId() == classId }

internal fun Sequence<KtFile>.topLevelFunctions(name: Name): Sequence<KtNamedFunction> =
	flatMap { it.declarations }
		.filterIsInstance<KtNamedFunction>()
		.filter { it.nameAsName == name }

internal fun Sequence<KtFile>.topLevelProperties(name: Name): Sequence<KtProperty> =
	flatMap { it.declarations }
		.filterIsInstance<KtProperty>()
		.filter { it.nameAsName == name }

internal fun Sequence<KtFile>.topLevelCallableNames(): Sequence<Name> =
	flatMap { it.declarations }
		.filterIsInstance<KtCallableDeclaration>()
		.mapNotNull { it.nameAsName }

internal fun Sequence<KtFile>.topLevelClassLikeNames(): Sequence<Name> =
	flatMap { it.declarations }
		.filterIsInstance<KtClassLikeDeclaration>()
		.mapNotNull { it.nameAsName }

private fun Sequence<KtFile>.declarationsAndNested(): Sequence<KtDeclaration> =
	flatMap { it.declarations }.flatMap { it.withNestedDeclarations() }

private fun KtDeclaration.withNestedDeclarations(): Sequence<KtDeclaration> =
	sequence {
		yield(this@withNestedDeclarations)
		if (this@withNestedDeclarations is KtClassOrObject) {
			for (member in declarations) yieldAll(member.withNestedDeclarations())
		}
	}

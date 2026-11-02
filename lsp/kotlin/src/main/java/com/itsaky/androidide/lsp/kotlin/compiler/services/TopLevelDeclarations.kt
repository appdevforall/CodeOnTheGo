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
 * Local declarations have no ClassId and are never top-level, so they never match. Callers must
 * hold the project read lock.
 */

internal fun Sequence<KtFile>.classesWithId(classId: ClassId): List<KtClassOrObject> =
	declarationsAndNested()
		.filterIsInstance<KtClassOrObject>()
		.filter { it.getClassId() == classId }
		.toList()

internal fun Sequence<KtFile>.typeAliasesWithId(classId: ClassId): List<KtTypeAlias> =
	declarationsAndNested()
		.filterIsInstance<KtTypeAlias>()
		.filter { it.getClassId() == classId }
		.toList()

internal fun Sequence<KtFile>.topLevelFunctions(name: Name): List<KtNamedFunction> =
	flatMap { it.declarations }
		.filterIsInstance<KtNamedFunction>()
		.filter { it.nameAsName == name }
		.toList()

internal fun Sequence<KtFile>.topLevelProperties(name: Name): List<KtProperty> =
	flatMap { it.declarations }
		.filterIsInstance<KtProperty>()
		.filter { it.nameAsName == name }
		.toList()

internal fun Sequence<KtFile>.topLevelCallableNames(): Set<Name> =
	flatMap { it.declarations }
		.filterIsInstance<KtCallableDeclaration>()
		.mapNotNull { it.nameAsName }
		.toSet()

internal fun Sequence<KtFile>.topLevelClassLikeNames(): Set<Name> =
	flatMap { it.declarations }
		.filterIsInstance<KtClassLikeDeclaration>()
		.mapNotNull { it.nameAsName }
		.toSet()

private fun Sequence<KtFile>.declarationsAndNested(): Sequence<KtDeclaration> =
	flatMap { it.declarations }.flatMap { it.withNestedDeclarations() }

private fun KtDeclaration.withNestedDeclarations(): Sequence<KtDeclaration> =
	sequence {
		yield(this@withNestedDeclarations)
		if (this@withNestedDeclarations is KtClassOrObject) {
			for (member in declarations) yieldAll(member.withNestedDeclarations())
		}
	}

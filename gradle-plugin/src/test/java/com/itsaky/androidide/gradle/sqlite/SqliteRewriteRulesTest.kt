package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.objectweb.asm.Type

class SqliteRewriteRulesTest {
	/**
	 * Rebuilds the descriptor from its parsed parts. `Type.getMethodType(d).descriptor` returns its input
	 * unchanged, so it accepts `(Q)V`; the rebuilt string differs, or ASM throws, for a malformed one.
	 */
	private fun isWellFormedDescriptor(desc: String): Boolean =
		runCatching {
			if (desc.startsWith("(")) {
				Type.getMethodDescriptor(Type.getReturnType(desc), *Type.getArgumentTypes(desc)) == desc
			} else {
				Type.getType(desc).descriptor == desc
			}
		}.getOrDefault(false)

	@Test
	fun `the descriptor check rejects a malformed descriptor and accepts a well-formed one`() {
		assertThat(isWellFormedDescriptor("(Q)V")).isFalse()
		assertThat(isWellFormedDescriptor("(Ljava/lang/String;I)V")).isTrue()
		assertThat(isWellFormedDescriptor("Q")).isFalse()
		assertThat(isWellFormedDescriptor("[Ljava/lang/String;")).isTrue()
	}

	@Test
	fun `every missing member lists the exact descriptors requery lacks, each a well-formed descriptor`() {
		// The rewriter fails a call or a field access only when its descriptor is in the entry's set, so an
		// entry with no descriptor, or a blank one, would match nothing.
		for ((member, descs) in SqliteRewriteRules.MISSING_MEMBERS) {
			assertWithMessage(member).that(descs).isNotEmpty()
			for (desc in descs) {
				assertWithMessage("$member descriptor '$desc'").that(isWellFormedDescriptor(desc)).isTrue()
			}
		}
	}

	@Test
	fun `the SQLite descriptor tokens, and which table matches fail on an unresolvable owner`() {
		assertThat(SqliteRewriteRules.SQLITE_DESCRIPTOR_TOKENS)
			.containsExactly(
				"Landroid/database/sqlite/",
				"Lio/requery/android/database/",
				"Landroid/database/Cursor;",
				"Landroid/os/CancellationSignal;",
				"Landroidx/core/os/CancellationSignal;",
				"Landroid/database/DatabaseErrorHandler;",
			).inOrder()
		val tokens = SqliteRewriteRules.SQLITE_DESCRIPTOR_TOKENS
		// a key is a name plus a descriptor, and no name holds a token
		val (failing, warned) = SqliteRewriteRules.INHERITABLE_RULE_MEMBERS.keys.partition { key -> tokens.any { key.contains(it) } }

		assertThat(failing.map { it.substringBefore('(') })
			.containsExactly("setOpenParams", "getCursorFactory", "insert", "update", "delete", "query")
		// CursorWindow is not an SQLite type: any windowed cursor has setWindow(CursorWindow) and mWindow
		assertThat(warned).hasSize(28)
		assertThat(warned).containsAtLeast(
			"isDistinct()Z",
			"isStrict()Z",
			"getUniqueId()I",
			"isString(I)Z",
			"isLong(I)Z",
			"isFloat(I)Z",
			"isBlob(I)Z",
			"getProjectionMap()Ljava/util/Map;",
			"setWindow(Landroid/database/CursorWindow;)V",
			"mWindowLandroid/database/CursorWindow;",
		)
	}

	private val collations =
		mapOf("UNICODE" to SqliteRewriteRules.COLLATE_UNICODE, "LOCALIZED" to SqliteRewriteRules.COLLATE_LOCALIZED)

	@Test
	fun `COLLATE accepts a quoted or bracketed name right after the keyword, a bare name needs a separator`() {
		for ((name, rule) in collations) {
			val quoted = listOf("COLLATE\"$name\"", "COLLATE'$name'", "COLLATE[$name]", "COLLATE`$name`", "collate\"${name.lowercase()}\"")
			for (form in quoted) {
				assertWithMessage(form).that(rule.containsMatchIn("SELECT 'a' $form")).isTrue()
			}
			for (form in listOf("COLLATE$name", "COLLATE$name x", "COLLATE\"$name-CI\"", "COLLATE'${name}_CI'", "COLLATE[$name-CI]")) {
				assertWithMessage(form).that(rule.containsMatchIn("SELECT 'a' $form")).isFalse()
			}
		}
	}
}

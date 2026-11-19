package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.objectweb.asm.Type

class SqliteRewriteRulesTest {
	@Test
	fun `every missing member lists the exact descriptors requery lacks, each a well-formed descriptor`() {
		// The rewriter fails a call or a field access only when its descriptor is in the entry's set, so an
		// entry with no descriptor, or a blank one, would match nothing.
		for ((member, descs) in SqliteRewriteRules.MISSING_MEMBERS) {
			assertWithMessage(member).that(descs).isNotEmpty()
			for (desc in descs) {
				val parsed = runCatching { if (desc.startsWith("(")) Type.getMethodType(desc) else Type.getType(desc) }.getOrNull()
				assertWithMessage("$member descriptor '$desc'").that(parsed?.descriptor).isEqualTo(desc)
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
				"Landroid/database/CursorWindow;",
				"Landroid/os/CancellationSignal;",
				"Landroidx/core/os/CancellationSignal;",
				"Landroid/database/DatabaseErrorHandler;",
			).inOrder()
		val tokens = SqliteRewriteRules.SQLITE_DESCRIPTOR_TOKENS
		// a key is a name plus a descriptor, and no name holds a token
		val (failing, warned) = SqliteRewriteRules.INHERITABLE_RULE_MEMBERS.keys.partition { key -> tokens.any { key.contains(it) } }

		assertThat(failing.map { it.substringBefore('(').removeSuffix("Landroid/database/CursorWindow;") })
			.containsExactly("setWindow", "mWindow", "setOpenParams", "getCursorFactory", "insert", "update", "delete", "query")
		assertThat(warned).hasSize(26)
		assertThat(warned).containsAtLeast(
			"isDistinct()Z",
			"isStrict()Z",
			"getUniqueId()I",
			"isString(I)Z",
			"isLong(I)Z",
			"isFloat(I)Z",
			"isBlob(I)Z",
			"getProjectionMap()Ljava/util/Map;",
		)
	}
}

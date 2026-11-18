package com.itsaky.androidide.gradle.sqlite

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
}

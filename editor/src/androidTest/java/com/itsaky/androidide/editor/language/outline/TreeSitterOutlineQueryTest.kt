package com.itsaky.androidide.editor.language.outline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryError
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import com.itsaky.androidide.treesitter.xml.TSLanguageXml
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TreeSitterOutlineQueryTest {
	@Before
	fun loadNative() {
		TreeSitter.loadLibrary()
	}

	@Test
	fun javaOutlineQueryCompiles() {
		assertCompiles("java", TSLanguageJava.getInstance())
	}

	@Test
	fun kotlinOutlineQueryCompiles() {
		assertCompiles("kt", TSLanguageKotlin.getInstance())
	}

	@Test
	fun xmlOutlineQueryCompiles() {
		assertCompiles("xml", TSLanguageXml.getInstance())
	}

	private fun assertCompiles(
		type: String,
		language: TSLanguage,
	) {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val scm =
			context.assets
				.open("editor/treesitter/$type/outline.scm")
				.reader()
				.readText()
		val query = TSQuery.create(language, scm)
		try {
			assertThat(query.errorType).isEqualTo(TSQueryError.None)
			assertThat(query.patternCount).isGreaterThan(0)
		} finally {
			query.close()
		}
	}
}

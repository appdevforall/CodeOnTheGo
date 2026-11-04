package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.SQLiteIndex
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A database written before the index stored package rows is rebuilt rather than reused. */
@RunWith(RobolectricTestRunner::class)
class JvmSymbolIndexFormatVersionTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	/** The descriptor as it was at the previous format: symbol rows only, no package rows. */
	private val descriptorWithoutPackages =
		object : IndexDescriptor<JvmSymbol> by JvmSymbolDescriptor {
			override fun packageOf(entry: JvmSymbol): String? = null
		}

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `an index stored before package rows existed is dropped on open`() =
		runTest {
			SQLiteIndex(
				descriptor = descriptorWithoutPackages,
				context = context,
				dbName = DB_NAME,
				formatVersion = FORMAT_WITHOUT_PACKAGES,
				name = INDEX_NAME,
			).use { it.insert(widget()) }

			JvmSymbolIndex.createSqliteIndex(context, DB_NAME, INDEX_NAME).use {
				it.setActiveSources(setOf(JAR))

				assertThat(it.isCached(JAR)).isFalse()
			}
		}

	@Test
	fun `a rebuilt index stores the package rows of what it indexes`() =
		runTest {
			JvmSymbolIndex.createSqliteIndex(context, DB_NAME, INDEX_NAME).use {
				it.insert(widget())
				it.setActiveSources(setOf(JAR))

				assertThat(it.containsPackage("com.example", sourceIds = null)).isTrue()
			}
		}

	private fun widget() =
		JvmSymbol(
			key = "com/example/Widget",
			sourceId = JAR,
			name = "com/example/Widget",
			shortName = "Widget",
			packageName = "com.example",
			kind = JvmSymbolKind.CLASS,
			language = JvmSourceLanguage.JAVA,
			data = JvmClassInfo(internalName = "com/example/Widget"),
		)

	private companion object {
		const val DB_NAME = "jvm_format_version_test.db"
		const val INDEX_NAME = "jvm-format-version-test"
		const val JAR = "widget.jar"

		/** The last format version whose rows carry no package tree. */
		const val FORMAT_WITHOUT_PACKAGES = 2
	}
}

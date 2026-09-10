package org.appdevforall.cotg.quickbuild.domain.annotations

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The scanner's two jobs: find every annotation with its arguments verbatim, and produce
 * a declaration fingerprint that moves when declarations move and holds still when only
 * a body, a comment or whitespace moves.
 */
class SourceAnnotationScannerTest {
	private fun scan(text: String) = SourceAnnotationScanner.scan(text)

	@Test
	fun `finds annotations with arguments and resolves package and imports`() {
		val facts =
			scan(
				"""
				package com.example

				import androidx.room.Entity
				import androidx.room.PrimaryKey

				@Entity(tableName = "users")
				data class User(@PrimaryKey val id: Long)
				""".trimIndent(),
			)!!

		assertThat(facts.packageName).isEqualTo("com.example")
		assertThat(facts.imports).containsExactly("androidx.room.Entity", "androidx.room.PrimaryKey")
		assertThat(facts.annotations.map { it.name }).containsExactly("Entity", "PrimaryKey").inOrder()
		assertThat(facts.annotations.first().arguments).isEqualTo("(tableName = \"users\")")
		assertThat(facts.declaredTypeNames).containsExactly("User")
	}

	@Test
	fun `keeps annotation string arguments verbatim`() {
		val sql = "@Query(\"SELECT * FROM users WHERE id = :id\")\nfun byId(id: Long): User"
		assertThat(scan(sql)!!.annotations.single().arguments)
			.isEqualTo("(\"SELECT * FROM users WHERE id = :id\")")
	}

	@Test
	fun `keeps nested parentheses in annotation arguments`() {
		val text = "@Entity(indices = [Index(value = [\"name\"])])\nclass User"
		assertThat(scan(text)!!.annotations.single().arguments)
			.isEqualTo("(indices = [Index(value = [\"name\"])])")
	}

	@Test
	fun `keeps kotlin use-site targets distinct`() {
		assertThat(scan("@field:Json(name = \"a\") val a: String")!!.annotations.single().name)
			.isEqualTo("Json")
	}

	@Test
	fun `ignores an at sign inside a string literal`() {
		assertThat(scan("val email = \"nobody@example.com\"")!!.annotations).isEmpty()
	}

	@Test
	fun `ignores annotations inside comments`() {
		val text =
			"""
			// @Entity
			/* @Dao */
			class Plain
			""".trimIndent()
		assertThat(scan(text)!!.annotations).isEmpty()
	}

	@Test
	fun `fingerprint ignores comments and whitespace`() {
		val a =
			"""
			class A {
				val x: Int = 1
			}
			""".trimIndent()
		val b =
			"""
			// leading note
			class A  {
				/* about x */
				val x: Int  =  1
			}
			""".trimIndent()

		assertThat(scan(a)!!.declarationFingerprint).isEqualTo(scan(b)!!.declarationFingerprint)
	}

	@Test
	fun `fingerprint ignores function bodies`() {
		val a =
			"""
			class A {
				fun go(): Int {
					return 1
				}
			}
			""".trimIndent()
		val b =
			"""
			class A {
				fun go(): Int {
					val doubled = 2 * 21
					return doubled
				}
			}
			""".trimIndent()

		assertThat(scan(a)!!.declarationFingerprint).isEqualTo(scan(b)!!.declarationFingerprint)
	}

	@Test
	fun `fingerprint moves when a declaration moves`() {
		val a = "class A {\n\tval x: Int = 1\n}"
		val b = "class A {\n\tval x: Long = 1\n}"

		assertThat(scan(a)!!.declarationFingerprint).isNotEqualTo(scan(b)!!.declarationFingerprint)
	}

	@Test
	fun `fingerprint keeps a nested class body`() {
		val a = "class A {\n\tclass Inner {\n\t\tval x: Int = 1\n\t}\n}"
		val b = "class A {\n\tclass Inner {\n\t\tval x: Long = 1\n\t}\n}"

		assertThat(scan(a)!!.declarationFingerprint).isNotEqualTo(scan(b)!!.declarationFingerprint)
	}

	@Test
	fun `fingerprint keeps a property initializer lambda`() {
		val a = "class A {\n\tval x = lazy {\n\t\t1\n\t}\n}"
		val b = "class A {\n\tval x = lazy {\n\t\t2\n\t}\n}"

		// Not a function signature, so the block is NOT treated as a body - conservative.
		assertThat(scan(a)!!.declarationFingerprint).isNotEqualTo(scan(b)!!.declarationFingerprint)
	}

	@Test
	fun `braces inside string literals do not confuse nesting`() {
		val facts = scan("class A {\n\tfun go(): String {\n\t\treturn \"{{{\"\n\t}\n}")
		assertThat(facts).isNotNull()
	}

	@Test
	fun `braces inside a raw string do not confuse nesting`() {
		val facts = scan("class A {\n\tval q = \"\"\"{ \"a\": 1 }\"\"\"\n}")
		assertThat(facts).isNotNull()
	}

	@Test
	fun `char literal brace does not confuse nesting`() {
		assertThat(scan("class A {\n\tval c = '{'\n}")).isNotNull()
	}

	@Test
	fun `unbalanced braces bail`() {
		assertThat(scan("class A {\n\tval x = 1\n")).isNull()
	}

	@Test
	fun `unterminated block comment bails`() {
		assertThat(scan("class A {}\n/* still going")).isNull()
	}

	@Test
	fun `unterminated raw string bails`() {
		assertThat(scan("val q = \"\"\"open")).isNull()
	}

	@Test
	fun `java method bodies are excluded from the fingerprint`() {
		val a =
			"""
			package com.example;

			public class A {
				public int go() {
					return 1;
				}
			}
			""".trimIndent()
		val b =
			"""
			package com.example;

			public class A {
				public int go() {
					int x = 21 * 2;
					return x;
				}
			}
			""".trimIndent()

		assertThat(scan(a)!!.declarationFingerprint).isEqualTo(scan(b)!!.declarationFingerprint)
	}

	@Test
	fun `java field change moves the fingerprint`() {
		val a = "public class A {\n\tint x = 1;\n}"
		val b = "public class A {\n\tlong x = 1;\n}"

		assertThat(scan(a)!!.declarationFingerprint).isNotEqualTo(scan(b)!!.declarationFingerprint)
	}

	@Test
	fun `records referenced type names from declarations and annotation arguments`() {
		val facts =
			scan(
				"""
				@Database(entities = [User::class], version = 1)
				abstract class AppDatabase : RoomDatabase()
				""".trimIndent(),
			)!!

		assertThat(facts.referencedTypeNames).containsAtLeast("User", "RoomDatabase", "AppDatabase")
	}
}

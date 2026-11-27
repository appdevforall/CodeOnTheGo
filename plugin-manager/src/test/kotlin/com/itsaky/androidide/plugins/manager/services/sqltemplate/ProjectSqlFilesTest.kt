package com.itsaky.androidide.plugins.manager.services.sqltemplate

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile

class ProjectSqlFilesTest {
	private val root = Files.createTempDirectory("project").toFile()

	@After
	fun cleanUp() {
		root.deleteRecursively()
	}

	private fun write(
		path: String,
		text: String,
	) = File(root, path).apply {
		parentFile!!.mkdirs()
		writeText(text)
	}

	/** Room 2.8 output for a database with one entity and nothing else: no indices, foreignKeys or views keys. */
	private fun schema(
		version: Int,
		hash: String,
	) = """
		{
		"formatVersion": 1,
		"database": {
			"version": $version,
			"identityHash": "$hash",
			"entities": [
			{
				"tableName": "User",
				"createSql": "CREATE TABLE IF NOT EXISTS `${'$'}{TABLE_NAME}` (`id` INTEGER NOT NULL, `name` TEXT, PRIMARY KEY(`id`))",
				"fields": [
				{"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
				{"fieldPath": "name", "columnName": "name", "affinity": "TEXT"}
				],
				"primaryKey": {"autoGenerate": false, "columnNames": ["id"]}
			}
			],
			"setupQueries": [
			"CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
			"INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$hash')"
			]
		}
		}
		""".trimIndent()

	@Test
	fun `a schema missing indices, foreignKeys and views reads them as empty`() {
		val s = ProjectSqlFiles.parseRoomSchema(schema(3, "abc"), "com.example.AppDatabase", "app/schemas/x/3.json")!!
		assertThat(s.className).isEqualTo("com.example.AppDatabase")
		assertThat(s.version).isEqualTo(3)
		assertThat(s.identityHash).isEqualTo("abc")
		assertThat(s.views).isEmpty()
		val user = s.entities.single()
		assertThat(user["tableName"]).isEqualTo("User")
		assertThat(user["indices"] as List<*>).isEmpty()
		assertThat(user["foreignKeys"] as List<*>).isEmpty()
		assertThat(user["createSql"] as String).contains("\${TABLE_NAME}")
		val fields = user["fields"] as List<*>
		assertThat((fields[0] as Map<*, *>)["notNull"]).isEqualTo(true)
		assertThat((user["primaryKey"] as Map<*, *>)["columnNames"]).isEqualTo(listOf("id"))
	}

	@Test
	fun `indices, foreignKeys and views that are there are kept`() {
		val text =
			"""
			{"formatVersion": 1, "database": {"version": 2, "identityHash": "h", "entities": [
			{"tableName": "Book", "fields": [], "primaryKey": {"autoGenerate": true, "columnNames": ["id"]},
			"indices": [{"name": "index_Book_userId", "unique": false, "columnNames": ["userId"]}],
			"foreignKeys": [{"table": "User", "onDelete": "CASCADE", "columns": ["userId"], "referencedColumns": ["id"]}]}],
			"views": [{"viewName": "Names", "createSql": "CREATE VIEW `${'$'}{VIEW_NAME}` AS SELECT name FROM User"}]}}
			""".trimIndent()
		val s = ProjectSqlFiles.parseRoomSchema(text, "Db", "2.json")!!
		val book = s.entities.single()
		assertThat(((book["indices"] as List<*>).single() as Map<*, *>)["name"]).isEqualTo("index_Book_userId")
		assertThat(((book["foreignKeys"] as List<*>).single() as Map<*, *>)["onDelete"]).isEqualTo("CASCADE")
		assertThat(s.views.single()["viewName"]).isEqualTo("Names")
		assertThat((book["primaryKey"] as Map<*, *>)["autoGenerate"]).isEqualTo(true)
	}

	@Test
	fun `JSON that is not Room's is not a schema`() {
		assertThat(ProjectSqlFiles.parseRoomSchema("""{"version": 1}""", "a.B", "1.json")).isNull()
		assertThat(ProjectSqlFiles.parseRoomSchema("""{"formatVersion": 1, "database": {"version": 1}}""", "a.B", "1.json")).isNull()
		assertThat(ProjectSqlFiles.parseRoomSchema("not json", "a.B", "1.json")).isNull()
	}

	@Test
	fun `finds the exported schemas, skipping build output and hidden folders`() {
		write("app/schemas/com.example.AppDatabase/1.json", schema(1, "h1"))
		write("app/schemas/com.example.AppDatabase/2.json", schema(2, "h2"))
		write("lib/schemas/com.example.CacheDb/1.json", schema(1, "c1"))
		write("app/build/intermediates/room/schemas/com.example.AppDatabase/3.json", schema(3, "copy"))
		write(".gradle/com.example.AppDatabase/4.json", schema(4, "hidden"))
		write("app/src/main/assets/data/1.json", """{"not": "room"}""")
		val found = ProjectSqlFiles.roomSchemas(root)
		assertThat(found.map { "${it.className} ${it.version} ${it.identityHash} ${it.path}" })
			.containsExactly(
				"com.example.AppDatabase 1 h1 app/schemas/com.example.AppDatabase/1.json",
				"com.example.AppDatabase 2 h2 app/schemas/com.example.AppDatabase/2.json",
				"com.example.CacheDb 1 c1 lib/schemas/com.example.CacheDb/1.json",
			).inOrder()
	}

	@Test
	fun `lists the sql templates`() {
		write("sqlite/report.sql.peb", "SELECT 1")
		write("app/sqlite/a.sql.peb", "SELECT 2")
		write("sqlite/notes.peb", "not sql")
		write("build/out.sql.peb", "copied")
		assertThat(ProjectSqlFiles.templates(root)).containsExactly("app/sqlite/a.sql.peb", "sqlite/report.sql.peb").inOrder()
	}

	@Test
	fun `a schema file nested too deep is skipped and the others are still found`() {
		val depth = ProjectSqlFiles.MAX_JSON_DEPTH * 2
		val head = """{"formatVersion": 1, "database": {"version": 1, "identityHash": "h", "entities": ["""
		val deep = head + "[".repeat(depth) + "]".repeat(depth) + "]}}"
		write("app/schemas/com.example.Deep/1.json", deep)
		write("app/schemas/com.example.Fine/1.json", schema(1, "ok"))
		assertThat(ProjectSqlFiles.roomSchemas(root).map { it.className }).containsExactly("com.example.Fine")
	}

	@Test
	fun `past the limit, the entries kept are the first in the documented order, wherever the walk met them`() {
		val per = ProjectSqlFiles.MAX_MATCHES * 3 / 4
		for (dir in listOf("a", "z")) for (i in 0 until per) write("$dir/t$i.sql.peb", "x")
		val all = (listOf("a", "z").flatMap { d -> (0 until per).map { "$d/t$it.sql.peb" } }).sorted()
		assertThat(ProjectSqlFiles.templates(root)).containsExactlyElementsIn(all.take(ProjectSqlFiles.MAX_MATCHES)).inOrder()
	}

	@Test
	fun `readBounded refuses a link, a folder and a file over the limit`() {
		val real = write("real.txt", "hello")
		assertThat(ProjectSqlFiles.readBounded(real)).isEqualTo("hello")
		val link = File(root, "link.txt")
		Files.createSymbolicLink(link.toPath(), real.toPath())
		assertThrows(java.io.IOException::class.java) { ProjectSqlFiles.readBounded(link) }
		assertThrows(java.io.IOException::class.java) { ProjectSqlFiles.readBounded(File(root, "nope")) }
		val folder = File(root, "dir").apply { mkdirs() }
		assertThrows(java.io.IOException::class.java) { ProjectSqlFiles.readBounded(folder) }
		val exact = write("exact.txt", "a".repeat(ProjectSqlFiles.MAX_FILE_BYTES.toInt()))
		assertThat(ProjectSqlFiles.readBounded(exact)).hasLength(ProjectSqlFiles.MAX_FILE_BYTES.toInt())
		val over = write("over.txt", "a".repeat(ProjectSqlFiles.MAX_FILE_BYTES.toInt() + 1))
		assertThrows(ProjectSqlFiles.TooLarge::class.java) { ProjectSqlFiles.readBounded(over) }
	}

	// minSdk is 28, and these InputStream methods are API 33: desugaring backports neither for this package
	// (app/build.gradle.kts gives JavaIOReplacements org.eclipse.jgit only), lint does not flag them here, and these
	// tests run on a JDK that has them. readAllBytes and transferTo are left out: Files and FileChannel have
	// older methods of those names.
	@Test
	fun `the template classes call no InputStream method that needs API 33`() {
		val type = ProjectSqlFiles::class.java
		val pkg = type.name.substringBeforeLast('.').replace('.', '/') + "/"
		val source = type.protectionDomain.codeSource
		val location = File(source.location.toURI())
		// The module's classes are a jar on the unit test classpath, or a folder.
		val classes: Map<String, ByteArray> =
			if (location.isDirectory) {
				File(location, pkg).listFiles { f -> f.name.endsWith(".class") }!!.associate { it.name to it.readBytes() }
			} else {
				JarFile(location).use { jar ->
					jar
						.entries()
						.toList()
						.filter { it.name.startsWith(pkg) && it.name.endsWith(".class") && '/' !in it.name.substring(pkg.length) }
						.associate { it.name.substring(pkg.length) to jar.getInputStream(it).use { s -> s.readBytes() } }
				}
			}
		assertThat(classes.keys).contains("ProjectSqlFiles.class")
		val calls =
			classes.flatMap { (name, bytes) ->
				val text = String(bytes, Charsets.ISO_8859_1)
				listOf("readNBytes", "skipNBytes").filter { it in text }.map { "$name: $it" }
			}
		assertThat(calls).isEmpty()
	}
}

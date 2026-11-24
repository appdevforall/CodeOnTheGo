package com.itsaky.androidide.assets.addons

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BundledAddonsRecordTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var file: File
	private lateinit var record: BundledAddonsRecord

	private val state =
		BundledAddonsState(
			fingerprint = "abc123",
			pluginIds = listOf("com.example.one", "com.example.two"),
			templateFiles = listOf("starter.cgt"),
		)

	@Before
	fun setUp() {
		file = File(tempFolder.root, "files/bundled_addons.properties")
		record = BundledAddonsRecord(file)
	}

	@Test
	fun `no file reads as no record`() {
		assertThat(record.read()).isNull()
		assertThat(record.unreportedFailures()).isEmpty()
		assertThat(record.markReported(listOf(BundledPluginFailure("com.example.one", "boom")))).isEmpty()
	}

	@Test
	fun `a written record reads back`() {
		record.write(state)

		assertThat(BundledAddonsRecord(file).read()).isEqualTo(state)
	}

	@Test
	fun `a thin record reads back with an empty bundle`() {
		val thin = BundledAddonsState(fingerprint = BundledAddons.fingerprint(emptyList()))

		record.write(thin)

		assertThat(record.read()).isEqualTo(thin)
	}

	@Test
	fun `writing replaces the whole record and leaves no temporary file`() {
		record.write(state)
		record.recordFailures(mapOf("com.example.one" to "boom"))

		val next = BundledAddonsState(fingerprint = "def456", pluginIds = listOf("com.example.three"))
		record.write(next)

		assertThat(record.read()).isEqualTo(next)
		assertThat(file.parentFile!!.list()!!.toList()).containsExactly(file.name)
	}

	@Test
	fun `an unreadable file reads as no record`() {
		file.parentFile!!.mkdirs()
		file.writeText("pluginX=\\u12")

		assertThat(record.read()).isNull()
	}

	@Test
	fun `a file without a fingerprint reads as no record`() {
		file.parentFile!!.mkdirs()
		file.writeText("plugin.0=com.example.one\n")

		assertThat(record.read()).isNull()
	}

	@Test
	fun `failures are stored unreported and kept with the bundle`() {
		record.write(state)

		record.recordFailures(linkedMapOf("com.example.one" to "boom", "com.example.two" to "bang"))

		assertThat(record.unreportedFailures())
			.containsExactly(
				BundledPluginFailure("com.example.one", "boom", reported = false),
				BundledPluginFailure("com.example.two", "bang", reported = false),
			).inOrder()
		assertThat(record.read()!!.copy(failures = emptyList())).isEqualTo(state)
	}

	@Test
	fun `failures with odd characters survive`() {
		record.write(state)
		val message = "line one\nline two = \"quoted\" \\ back: é"

		record.recordFailures(mapOf("com.example.one" to message))

		assertThat(record.unreportedFailures().single().message).isEqualTo(message)
	}

	@Test
	fun `markReported hands the failures out once`() {
		record.write(state)
		record.recordFailures(mapOf("com.example.one" to "boom"))

		val told = record.unreportedFailures()
		val first = record.markReported(told)
		val second = record.markReported(told)

		assertThat(first).containsExactly(BundledPluginFailure("com.example.one", "boom", reported = false))
		assertThat(second).isEmpty()
		assertThat(record.unreportedFailures()).isEmpty()
		assertThat(record.read()!!.failures).containsExactly(BundledPluginFailure("com.example.one", "boom", reported = true))
	}

	@Test
	fun `a new failure after a report is unreported again`() {
		record.write(state)
		record.recordFailures(mapOf("com.example.one" to "boom"))
		record.markReported(record.unreportedFailures())

		record.recordFailures(mapOf("com.example.two" to "bang"))

		assertThat(record.unreportedFailures()).containsExactly(BundledPluginFailure("com.example.two", "bang", reported = false))
	}

	@Test
	fun `markReported leaves a failure recorded after the told ones were read`() {
		record.write(state)
		record.recordFailures(mapOf("com.example.one" to "boom"))
		val told = record.unreportedFailures()

		record.recordFailures(mapOf("com.example.two" to "bang"))
		val marked = record.markReported(told)

		assertThat(marked).containsExactly(BundledPluginFailure("com.example.one", "boom"))
		assertThat(record.unreportedFailures()).containsExactly(BundledPluginFailure("com.example.two", "bang"))
	}

	@Test
	fun `markReported leaves a failure recorded again with another message`() {
		record.write(state)
		record.recordFailures(mapOf("com.example.one" to "boom"))
		val told = record.unreportedFailures()

		record.recordFailures(mapOf("com.example.one" to "bang"))
		record.markReported(told)

		assertThat(record.unreportedFailures()).containsExactly(BundledPluginFailure("com.example.one", "bang"))
	}

	@Test
	fun `recording load failures keeps failures recorded under other keys`() {
		record.write(state.copy(failures = listOf(BundledPluginFailure("broken.cgp", "it has no readable manifest"))))

		record.recordFailures(mapOf("com.example.one" to "boom"))

		assertThat(record.unreportedFailures()).containsExactly(
			BundledPluginFailure("broken.cgp", "it has no readable manifest"),
			BundledPluginFailure("com.example.one", "boom"),
		)
	}

	@Test
	fun `recording failures without a record does nothing`() {
		record.recordFailures(mapOf("com.example.one" to "boom"))

		assertThat(file.exists()).isFalse()
	}

	@Test
	fun `every caller of one files folder shares one record, so its lock covers them all`() {
		val filesDir = File(tempFolder.root, "files")

		assertThat(BundledAddonsRecord.inFilesDir(filesDir)).isSameInstanceAs(BundledAddonsRecord.inFilesDir(File(filesDir, ".")))
	}
}

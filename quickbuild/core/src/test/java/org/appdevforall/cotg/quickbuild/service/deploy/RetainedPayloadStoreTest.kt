package org.appdevforall.cotg.quickbuild.service.deploy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The retention contract the reconnect re-send stands on: what [RetainedPayloadStore.load]
 * hands back is exactly what a confirmed deploy [RetainedPayloadStore.retain]ed - or null,
 * never a mix. A half-readable set re-sent to the proxy app would advance it past classes it
 * never received, so every corruption case must collapse to "nothing retained".
 */
class RetainedPayloadStoreTest {
	@TempDir lateinit var workDir: File

	private val store by lazy { RetainedPayloadStore.forWorkDir(workDir) }

	private fun artifact(
		name: String,
		content: String,
	): File = File(workDir, name).apply { writeText(content) }

	@Test
	fun `retain and load round-trip the payload bytes, generation and metadata`() {
		val dex = artifact("built.dex", "dex-bytes")
		val arsc = artifact("built.arsc", "arsc-bytes")
		val assets = artifact("built-assets.zip", "assets-bytes")

		store.retain(7L, dex, arsc, assets, """{"entryActivity":"com.example.Main"}""")
		val loaded = store.load()!!

		assertThat(loaded.generation).isEqualTo(7L)
		assertThat(loaded.metadataJson).isEqualTo("""{"entryActivity":"com.example.Main"}""")
		assertThat(loaded.dexFile!!.readText()).isEqualTo("dex-bytes")
		assertThat(loaded.arscFile!!.readText()).isEqualTo("arsc-bytes")
		assertThat(loaded.assetsZip!!.readText()).isEqualTo("assets-bytes")
	}

	@Test
	fun `retained bytes are copies - overwriting the build artifact does not change them`() {
		// The executor's next build overwrites its own artifacts in place; retention that
		// merely pointed at them would silently re-send the NEWER, unconfirmed bytes.
		val dex = artifact("built.dex", "generation-3-bytes")
		store.retain(3L, dex, null, null, "{}")

		dex.writeText("generation-4-bytes-from-a-build-that-never-deployed")

		assertThat(store.load()!!.dexFile!!.readText()).isEqualTo("generation-3-bytes")
	}

	@Test
	fun `a payload part the deploy did not carry loads back as null, not as a failure`() {
		store.retain(2L, artifact("built.dex", "dex"), null, null, "{}")
		val loaded = store.load()!!

		assertThat(loaded.dexFile).isNotNull()
		assertThat(loaded.arscFile).isNull()
		assertThat(loaded.assetsZip).isNull()
	}

	@Test
	fun `retain replaces the previous set wholesale`() {
		store.retain(1L, artifact("built.dex", "old-dex"), null, artifact("a.zip", "old-assets"), "{}")
		store.retain(2L, artifact("built2.dex", "new-dex"), null, null, "{}")

		val loaded = store.load()!!
		assertThat(loaded.generation).isEqualTo(2L)
		assertThat(loaded.dexFile!!.readText()).isEqualTo("new-dex")
		// The old set's assets zip must not leak into the new set: the deploy it rode
		// carried none.
		assertThat(loaded.assetsZip).isNull()
	}

	@Test
	fun `nothing retained loads as null`() {
		assertThat(store.load()).isNull()
	}

	@Test
	fun `corrupt metadata loads as null instead of throwing`() {
		store.retain(1L, artifact("built.dex", "dex"), null, null, "{}")
		File(File(workDir, "last-deployed"), "meta.json").writeText("not json {")

		assertThat(store.load()).isNull()
	}

	@Test
	fun `a part the metadata claims but the directory lacks makes the whole set unreadable`() {
		store.retain(1L, artifact("built.dex", "dex"), null, null, "{}")
		File(File(workDir, "last-deployed"), "payload.dex").delete()

		assertThat(store.load()).isNull()
	}

	@Test
	fun `a failed retain keeps nothing partial`() {
		// The dex file vanishes before retain can copy it - the copy throws mid-swap.
		val dex = File(workDir, "gone.dex")
		store.retain(5L, dex, null, null, "{}")

		assertThat(store.load()).isNull()
	}

	@Test
	fun `clear drops the retained set`() {
		store.retain(1L, artifact("built.dex", "dex"), null, null, "{}")
		store.clear()

		assertThat(store.load()).isNull()
	}
}

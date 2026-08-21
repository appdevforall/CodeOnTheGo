package org.appdevforall.cotg.quickbuild.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The wire-contract details of the request/result DTOs that the codec and the client (both
 * in other modules) rely on: documented defaults for optional fields (an absent field must
 * mean the documented fallback behavior, never an error) and value semantics (a request
 * rebuilt from the same wire fields IS the same request - what every codec round-trip
 * assertion stands on).
 */
class DaemonProtocolDtoTest {
	@Test
	fun `configure without optional toolchain paths means self-discovery and the v1 minApi floor`() {
		val request = ConfigureRequest(1, "/p", listOf("/a.jar"), "/out")

		// Null here is the "discover from ANDROID_HOME" signal, not an error.
		assertThat(request.aapt2).isNull()
		assertThat(request.d8Jar).isNull()
		assertThat(request.androidJar).isNull()
		assertThat(request.minApi).isEqualTo(30)
		assertThat(request.minApi).isEqualTo(ConfigureRequest.DEFAULT_MIN_API)
		assertThat(request.compilerPlugins).isEmpty()
	}

	@Test
	fun `configure carries explicit toolchain paths and session inputs verbatim`() {
		val request =
			ConfigureRequest(
				id = 9,
				projectRoot = "/projects/demo",
				classpath = listOf("/android.jar", "/kotlin-stdlib.jar"),
				outDir = "/work",
				aapt2 = "/sdk/aapt2",
				d8Jar = "/sdk/r8.jar",
				androidJar = "/sdk/android.jar",
				minApi = 26,
				compilerPlugins = listOf("/compose-compiler-plugin.jar"),
			)

		assertThat(request.id).isEqualTo(9)
		assertThat(request.projectRoot).isEqualTo("/projects/demo")
		assertThat(request.classpath).containsExactly("/android.jar", "/kotlin-stdlib.jar").inOrder()
		assertThat(request.outDir).isEqualTo("/work")
		assertThat(request.aapt2).isEqualTo("/sdk/aapt2")
		assertThat(request.d8Jar).isEqualTo("/sdk/r8.jar")
		assertThat(request.androidJar).isEqualTo("/sdk/android.jar")
		assertThat(request.minApi).isEqualTo(26)
		assertThat(request.compilerPlugins).containsExactly("/compose-compiler-plugin.jar")
	}

	@Test
	fun `compile defaults removedFiles to empty - the pre-removal-support behavior`() {
		val request = CompileRequest(2, listOf("/A.kt", "/B.kt"), listOf("/A.kt"))

		assertThat(request.id).isEqualTo(2)
		assertThat(request.allSources).containsExactly("/A.kt", "/B.kt").inOrder()
		assertThat(request.changedFiles).containsExactly("/A.kt")
		assertThat(request.removedFiles).isEmpty()
	}

	@Test
	fun `relink defaults stableIds to null and libraryResources to empty - the documented fallbacks`() {
		val request = RelinkRequest(4, listOf("/res"), "/AndroidManifest.xml")

		assertThat(request.id).isEqualTo(4)
		assertThat(request.resDirs).containsExactly("/res")
		assertThat(request.manifest).isEqualTo("/AndroidManifest.xml")
		// Null = unpinned relink (pre-Bug-6), empty = project res only (pre-Bug-8):
		// documented protocol behavior, not an error.
		assertThat(request.stableIds).isNull()
		assertThat(request.libraryResources).isEmpty()
	}

	@Test
	fun `every request exposes its id through DaemonRequest - the correlation contract`() {
		// The router and client correlate responses purely by this polymorphic id.
		val requests: List<DaemonRequest> =
			listOf(
				ConfigureRequest(11, "/p", emptyList(), "/out"),
				CompileRequest(12, emptyList(), emptyList()),
				DexRequest(13, listOf("/classes")),
				RelinkRequest(14, listOf("/res"), "/M.xml"),
				PingRequest(15),
				ShutdownRequest(16),
			)

		assertThat(requests.map { it.id }).containsExactly(11L, 12L, 13L, 14L, 15L, 16L).inOrder()
		assertThat((requests[2] as DexRequest).classesDirs).containsExactly("/classes")
	}

	@Test
	fun `requests rebuilt from the same wire fields are equal - value semantics`() {
		// Codec round-trip tests compare a re-parsed request to the original; that only
		// proves anything because these are value types, pinned here.
		assertThat(ConfigureRequest(1, "/p", listOf("/a.jar"), "/out"))
			.isEqualTo(ConfigureRequest(1, "/p", listOf("/a.jar"), "/out"))
		assertThat(PingRequest(5)).isEqualTo(PingRequest(5))
		assertThat(ShutdownRequest(5)).isNotEqualTo(ShutdownRequest(6))
		assertThat(DexRequest(3, listOf("/classes"))).isNotEqualTo(DexRequest(3, listOf("/other")))
	}

	@Test
	fun `a diagnostic without a location is just a severity and message`() {
		val diagnostic = Diagnostic(Diagnostic.Severity.ERROR, "boom")

		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostic.message).isEqualTo("boom")
		assertThat(diagnostic.file).isNull()
		assertThat(diagnostic.line).isNull()
		assertThat(diagnostic.column).isNull()
	}

	@Test
	fun `parsed wraps the request it recovered`() {
		val parsed = ParseResult.Parsed(PingRequest(3))

		assertThat(parsed.request).isEqualTo(PingRequest(3))
	}

	@Test
	fun `malformed keeps the recovered id and the reason - and the unknown id is -1`() {
		val malformed = ParseResult.Malformed(7, "missing 'op'")

		assertThat(malformed.id).isEqualTo(7)
		assertThat(malformed.message).isEqualTo("missing 'op'")
		// -1 is on the wire whenever the id could not be recovered; the client keys its
		// "something failed but I don't know what" handling on this exact value.
		assertThat(ParseResult.Malformed.UNKNOWN_ID).isEqualTo(-1L)
	}

	@Test
	fun `a directly constructed response defaults to no values and no diagnostics`() {
		val direct = DaemonResponse(4, true)
		val helper = DaemonResponse.ok(5)

		assertThat(direct.values).isEmpty()
		assertThat(direct.diagnostics).isEmpty()
		assertThat(helper.ok).isTrue()
		assertThat(helper.values).isEmpty()
		assertThat(helper.diagnostics).isEmpty()
	}

	@Test
	fun `an unmeasured CompileStats serializes every key as zero, not as absent`() {
		// The absent-vs-zero convention: a daemon that HAS the stats fields always writes
		// all keys (zeros mean "measured, and it was free"); only a daemon predating the
		// fields omits them (fromValues then yields null). A default row must therefore
		// serialize all-zero, never skip keys.
		val values = CompileStats().toValues()

		assertThat(values.keys)
			.containsExactly(
				CompileStats.KEY_PRE_SNAP_MILLIS,
				CompileStats.KEY_POST_SNAP_MILLIS,
				CompileStats.KEY_JAVA_ABI_SNAP_MILLIS,
				CompileStats.KEY_ALL_SOURCES,
				CompileStats.KEY_KOTLIN_TO_COMPILE,
				CompileStats.KEY_JAVA_SOURCES,
				CompileStats.KEY_CHANGED_CLASSES,
				CompileStats.KEY_COMPILE_ORDINAL,
			)
		assertThat(values.values.map { (it as Number).toLong() }).containsExactlyElementsIn(LongArray(8).toList())
	}

	@Test
	fun `fromValues defaults a missing ordinal to zero when another key is present`() {
		// The mirror of the existing ordinal-only test: any single surviving key keeps the
		// row alive, and the ORDINAL side of the per-key elvis must also fill with 0.
		val stats =
			CompileStats.fromValues { key ->
				if (key == CompileStats.KEY_PRE_SNAP_MILLIS) 42L else null
			}

		assertThat(stats).isEqualTo(CompileStats(preSnapMillis = 42, compileOrdinal = 0))
	}

	@Test
	fun `an unmeasured DexStats serializes both keys as zero`() {
		assertThat(DexStats().toValues())
			.containsExactly(DexStats.KEY_CLASS_FILES, 0, DexStats.KEY_CLASS_BYTES, 0L)
	}

	@Test
	fun `DexStats fromValues with only classBytes fills classFiles with zero`() {
		val stats =
			DexStats.fromValues { key ->
				if (key == DexStats.KEY_CLASS_BYTES) 1_234L else null
			}

		assertThat(stats).isEqualTo(DexStats(classFiles = 0, classBytes = 1_234))
	}
}

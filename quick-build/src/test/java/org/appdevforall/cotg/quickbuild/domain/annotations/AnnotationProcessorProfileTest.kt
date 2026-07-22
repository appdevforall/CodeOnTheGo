package org.appdevforall.cotg.quickbuild.domain.annotations

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Which annotations a given processor set claims - the permissive/conservative switch. */
class AnnotationProcessorProfileTest {
	private fun factsOf(text: String) = SourceAnnotationScanner.scan(text)!!

	private fun isInput(
		profile: AnnotationProcessorProfile,
		text: String,
	): Boolean {
		val facts = factsOf(text)
		return facts.annotations.any { profile.isProcessorInput(it, facts) }
	}

	private val room = AnnotationProcessorProfile.of(listOf("androidx.room:room-compiler:2.6.1"))

	@Test
	fun `empty coordinates mean no processors`() {
		assertThat(AnnotationProcessorProfile.of(emptyList()).hasProcessors).isFalse()
		assertThat(AnnotationProcessorProfile.of(listOf("  ")).hasProcessors).isFalse()
	}

	@Test
	fun `room claims its own annotations`() {
		assertThat(isInput(room, "import androidx.room.Entity\n@Entity\nclass User")).isTrue()
	}

	@Test
	fun `room does not claim a compose annotation`() {
		assertThat(
			isInput(room, "import androidx.compose.runtime.Composable\n@Composable\nfun Screen()"),
		).isFalse()
	}

	@Test
	fun `a qualified use site resolves without an import`() {
		assertThat(isInput(room, "@androidx.room.Dao\ninterface UserDao")).isTrue()
	}

	@Test
	fun `an unresolvable name falls back to the processor vocabulary`() {
		// No import at all: only the simple name is available.
		assertThat(isInput(room, "@Dao\ninterface UserDao")).isTrue()
		assertThat(isInput(room, "@Parcelize\nclass Thing")).isFalse()
	}

	@Test
	fun `a version catalog alias still identifies the processor`() {
		val profile = AnnotationProcessorProfile.of(listOf("libs.room.compiler"))
		assertThat(isInput(profile, "import androidx.room.Dao\n@Dao\ninterface UserDao")).isTrue()
	}

	@Test
	fun `an unrecognized processor claims every non language annotation`() {
		val profile = AnnotationProcessorProfile.of(listOf("com.example:mystery:1.0"))
		assertThat(isInput(profile, "import androidx.compose.runtime.Composable\n@Composable\nfun S()")).isTrue()
		assertThat(isInput(profile, "@Whatever\nclass Thing")).isTrue()
	}

	@Test
	fun `an unrecognized processor still ignores language level annotations`() {
		val profile = AnnotationProcessorProfile.of(listOf("com.example:mystery:1.0"))
		assertThat(isInput(profile, "@Deprecated(\"x\")\nfun old()")).isFalse()
		assertThat(isInput(profile, "@Suppress(\"UNCHECKED_CAST\")\nfun cast()")).isFalse()
		assertThat(isInput(profile, "import java.lang.Override\n@Override\nfun go()")).isFalse()
	}

	@Test
	fun `mixing a recognized and an unrecognized processor stays conservative`() {
		val profile =
			AnnotationProcessorProfile.of(
				listOf("androidx.room:room-compiler:2.6.1", "com.example:mystery:1.0"),
			)
		assertThat(isInput(profile, "import androidx.compose.runtime.Composable\n@Composable\nfun S()")).isTrue()
	}

	@Test
	fun `hilt and dagger share a vocabulary`() {
		val profile = AnnotationProcessorProfile.of(listOf("com.google.dagger:hilt-android-compiler:2.51"))
		assertThat(isInput(profile, "import dagger.hilt.android.AndroidEntryPoint\n@AndroidEntryPoint\nclass A")).isTrue()
		assertThat(isInput(profile, "import javax.inject.Inject\n@Inject\nlateinit var x: String")).isTrue()
		assertThat(isInput(profile, "import androidx.room.Entity\n@Entity\nclass User")).isFalse()
	}

	@Test
	fun `moshi claims its json annotations`() {
		val profile = AnnotationProcessorProfile.of(listOf("com.squareup.moshi:moshi-kotlin-codegen:1.15.0"))
		assertThat(isInput(profile, "import com.squareup.moshi.JsonClass\n@JsonClass(generateAdapter = true)\nclass A")).isTrue()
	}

	@Test
	fun `no processors claims nothing at all`() {
		assertThat(isInput(AnnotationProcessorProfile.NONE, "import androidx.room.Entity\n@Entity\nclass U")).isFalse()
	}
}

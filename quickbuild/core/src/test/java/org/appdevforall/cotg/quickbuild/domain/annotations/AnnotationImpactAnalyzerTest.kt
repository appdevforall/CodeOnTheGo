package org.appdevforall.cotg.quickbuild.domain.annotations

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.ChangeClassifier
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The correctness contract of annotation-aware classification, exercised against a
 * realistic Room + Hilt fixture on disk.
 *
 * The asymmetry to keep in mind while reading: a wrong "safe" ships stale generated code
 * (a never-stale violation), while a wrong "rebaseline" only costs ~8 s. Every ambiguous
 * case below therefore asserts the rebaseline.
 */
class AnnotationImpactAnalyzerTest {
	@TempDir
	lateinit var root: File

	private val roomProfile = AnnotationProcessorProfile.of(listOf("androidx.room:room-compiler:2.6.1"))

	private fun analyzer(
		fixture: RoomAppFixture,
		profile: AnnotationProcessorProfile = roomProfile,
	): AnnotationImpactAnalyzer = AnnotationImpactAnalyzer(profile, AnnotationBaseline.capture(fixture.all, profile))

	private fun fixture(): RoomAppFixture = RoomAppFixture(root)

	@Test
	fun `no processors configured is inactive and never escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture, AnnotationProcessorProfile.NONE)
		fixture.edit(fixture.userDao, RoomAppFixture.USER_DAO.replace("ORDER BY name", "ORDER BY id"))

		assertThat(analyzer.active).isFalse()
		assertThat(analyzer.escalation(listOf(fixture.userDao))).isNull()
	}

	@Test
	fun `unedited annotated file does not escalate`() {
		val fixture = fixture()
		assertThat(analyzer(fixture).escalation(fixture.all)).isNull()
	}

	@Test
	fun `editing Query SQL escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(fixture.userDao, RoomAppFixture.USER_DAO.replace("ORDER BY name", "ORDER BY id"))

		assertThat(analyzer.escalation(listOf(fixture.userDao))).contains("UserDao.kt")
	}

	@Test
	fun `adding an entity column escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.user,
			RoomAppFixture.USER.replace("\tval name: String,", "\tval name: String,\n\tval nickname: String,"),
		)

		assertThat(analyzer.escalation(listOf(fixture.user))).isNotNull()
	}

	@Test
	fun `adding a Dao method escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.userDao,
			RoomAppFixture.USER_DAO.replace(
				"\t@Insert",
				"\t@Query(\"SELECT COUNT(*) FROM users\")\n\tfun count(): Int\n\n\t@Insert",
			),
		)

		assertThat(analyzer.escalation(listOf(fixture.userDao))).isNotNull()
	}

	@Test
	fun `removing an annotation escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(fixture.user, RoomAppFixture.USER.replace("@PrimaryKey val id", "val id"))

		assertThat(analyzer.escalation(listOf(fixture.user))).isNotNull()
	}

	@Test
	fun `new file carrying an entity annotation escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		val note =
			fixture.write(
				"Note.kt",
				"""
				package com.example.notes

				import androidx.room.Entity
				import androidx.room.PrimaryKey

				@Entity
				data class Note(@PrimaryKey val id: Long, val body: String)
				""".trimIndent(),
			)

		assertThat(analyzer.escalation(listOf(note))).contains("new file")
	}

	@Test
	fun `new file without processor annotations stays on the live reload path`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		val helper =
			fixture.write(
				"Strings.kt",
				"""
				package com.example.notes

				object Strings {
					fun shout(value: String): String {
						return value.uppercase()
					}
				}
				""".trimIndent(),
			)

		assertThat(analyzer.escalation(listOf(helper))).isNull()
	}

	@Test
	fun `deleting an annotated file escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		assertThat(fixture.userDao.delete()).isTrue()

		assertThat(analyzer.escalation(listOf(fixture.userDao))).contains("deleted")
	}

	@Test
	fun `an anchor file reported as changed but byte-identical stays on the live reload path`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		// A watcher event with no real content change (touch, editor re-save).
		fixture.edit(fixture.address, RoomAppFixture.ADDRESS)

		assertThat(analyzer.escalation(listOf(fixture.address))).isNull()
	}

	@Test
	fun `deleting an anchor file escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		assertThat(fixture.address.delete()).isTrue()

		assertThat(analyzer.escalation(listOf(fixture.address))).contains("Address")
	}

	@Test
	fun `adding a declaration to an anchor file escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.baseEntity,
			RoomAppFixture.BASE_ENTITY.replace(
				"var createdAt: Long = 0",
				"var createdAt: Long = 0\n\n\tfun touch() {\n\t\tcreatedAt = 1\n\t}",
			),
		)

		assertThat(analyzer.escalation(listOf(fixture.baseEntity))).isNotNull()
	}

	@Test
	fun `deleting a plain file stays on the live reload path`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		assertThat(fixture.formatter.delete()).isTrue()

		assertThat(analyzer.escalation(listOf(fixture.formatter))).isNull()
	}

	@Test
	fun `body-only edit of a plain UI file stays on the live reload path`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(fixture.activity, RoomAppFixture.ACTIVITY.replace("\"Notes\"", "\"My Notes\""))

		assertThat(analyzer.escalation(listOf(fixture.activity))).isNull()
	}

	@Test
	fun `body-only edit inside an annotated file stays on the live reload path`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.converters,
			RoomAppFixture.CONVERTERS.replace("return value?.toString()", "return value?.toString()?.trim()"),
		)

		assertThat(analyzer.escalation(listOf(fixture.converters))).isNull()
	}

	@Test
	fun `changing a converter signature escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.converters,
			RoomAppFixture.CONVERTERS.replace("fun fromTimestamp(value: Long?): String?", "fun fromTimestamp(value: Int?): String?"),
		)

		assertThat(analyzer.escalation(listOf(fixture.converters))).isNotNull()
	}

	@Test
	fun `comment and whitespace edits stay on the live reload path`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.userDao,
			RoomAppFixture.USER_DAO
				.replace("@Dao", "/** The users table. */\n@Dao")
				.replace("interface UserDao {", "interface UserDao  {\n"),
		)

		assertThat(analyzer.escalation(listOf(fixture.userDao))).isNull()
	}

	@Test
	fun `import-only change that brings nothing processor-relevant into scope stays on the live reload path`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.viewModel,
			RoomAppFixture.VIEW_MODEL.replace("package com.example.notes", "package com.example.notes\n\nimport kotlin.math.max"),
		)

		assertThat(analyzer.escalation(listOf(fixture.viewModel))).isNull()
	}

	@Test
	fun `import change that brings a Room annotation into scope escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.formatter,
			"""
			package com.example.notes

			import androidx.room.Entity

			@Entity
			data class Formatted(val id: Long)
			""".trimIndent(),
		)

		assertThat(analyzer.escalation(listOf(fixture.formatter))).isNotNull()
	}

	@Test
	fun `editing an Embedded value type escalates even though it has no annotation`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(fixture.address, RoomAppFixture.ADDRESS.replace("val city: String,", "val city: String,\n\tval zip: String,"))

		assertThat(analyzer.escalation(listOf(fixture.address))).contains("Address")
	}

	@Test
	fun `editing a non-annotated entity base class escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(
			fixture.baseEntity,
			RoomAppFixture.BASE_ENTITY.replace("var createdAt: Long = 0", "var createdAt: Long = 0\n\tvar updatedAt: Long = 0"),
		)

		assertThat(analyzer.escalation(listOf(fixture.baseEntity))).contains("BaseEntity")
	}

	@Test
	fun `a batch escalates when any one file touches processor input`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(fixture.activity, RoomAppFixture.ACTIVITY.replace("\"Notes\"", "\"My Notes\""))
		fixture.edit(fixture.userDao, RoomAppFixture.USER_DAO.replace("ORDER BY name", "ORDER BY id"))

		assertThat(analyzer.escalation(listOf(fixture.activity, fixture.userDao))).isNotNull()
	}

	@Test
	fun `an unscannable edit escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		// Mid-typing state: the closing brace has not been typed yet.
		fixture.edit(fixture.activity, RoomAppFixture.ACTIVITY.dropLast(1))

		assertThat(analyzer.escalation(listOf(fixture.activity))).contains("could not be scanned")
	}

	@Test
	fun `an unrecognized processor treats any annotation as input`() {
		val fixture = fixture()
		val profile = AnnotationProcessorProfile.of(listOf("com.example:mystery-processor:1.0"))
		val analyzer = analyzer(fixture, profile)
		fixture.edit(
			fixture.formatter,
			"""
			package com.example.notes

			import com.example.mystery.Magic

			@Magic
			object Formatter {
				fun format(name: String): String {
					return name.trim()
				}
			}
			""".trimIndent(),
		)

		assertThat(analyzer.escalation(listOf(fixture.formatter))).isNotNull()
	}

	@Test
	fun `an unrecognized processor still live-reloads a file with no annotations`() {
		val fixture = fixture()
		val profile = AnnotationProcessorProfile.of(listOf("com.example:mystery-processor:1.0"))
		val analyzer = analyzer(fixture, profile)
		fixture.edit(fixture.formatter, RoomAppFixture.FORMATTER.replace("name.trim()", "name.trim().lowercase()"))

		assertThat(analyzer.escalation(listOf(fixture.formatter))).isNull()
	}

	@Test
	fun `an unrecognized processor ignores language-level annotations`() {
		val fixture = fixture()
		val profile = AnnotationProcessorProfile.of(listOf("com.example:mystery-processor:1.0"))
		val analyzer = analyzer(fixture, profile)
		fixture.edit(
			fixture.formatter,
			"""
			package com.example.notes

			object Formatter {
				@Deprecated("use format2")
				fun format(name: String): String {
					return name.trim()
				}
			}
			""".trimIndent(),
		)

		assertThat(analyzer.escalation(listOf(fixture.formatter))).isNull()
	}

	@Test
	fun `reverting an annotation edit returns to the live reload path`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		fixture.edit(fixture.userDao, RoomAppFixture.USER_DAO.replace("ORDER BY name", "ORDER BY id"))
		assertThat(analyzer.escalation(listOf(fixture.userDao))).isNotNull()

		fixture.edit(fixture.userDao, RoomAppFixture.USER_DAO)
		assertThat(analyzer.escalation(listOf(fixture.userDao))).isNull()
	}

	@Test
	fun `hilt entry point on an activity live-reloads a body edit but not a constructor change`() {
		val fixture = fixture()
		val profile = AnnotationProcessorProfile.of(listOf("com.google.dagger:hilt-android-compiler:2.51"))
		val hiltActivity =
			fixture.write(
				"HiltActivity.kt",
				HILT_ACTIVITY,
			)
		val baseline = AnnotationBaseline.capture(fixture.all + hiltActivity, profile)
		val analyzer = AnnotationImpactAnalyzer(profile, baseline)

		fixture.edit(hiltActivity, HILT_ACTIVITY.replace("\"Hilt\"", "\"Hilt App\""))
		assertThat(analyzer.escalation(listOf(hiltActivity))).isNull()

		fixture.edit(hiltActivity, HILT_ACTIVITY.replace("val dao: UserDao", "val dao: UserDao, val converters: Converters"))
		assertThat(analyzer.escalation(listOf(hiltActivity))).isNotNull()
	}

	@Test
	fun `the classifier routes a real Room edit set through the analyzer`() {
		val fixture = fixture()
		val classifier = ChangeClassifier(analyzer(fixture))
		fixture.edit(fixture.activity, RoomAppFixture.ACTIVITY.replace("\"Notes\"", "\"My Notes\""))

		assertThat(classifier.classify(ChangedFiles.Known(setOf(fixture.activity))))
			.isEqualTo(BuildRoute.CodeOnly)

		fixture.edit(fixture.userDao, RoomAppFixture.USER_DAO.replace("ORDER BY name", "ORDER BY id"))

		assertThat(classifier.classify(ChangedFiles.Known(setOf(fixture.activity, fixture.userDao))))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED))
	}

	/**
	 * A file the baseline knows but could not scan has no old facts to compare against, so
	 * nothing can prove the edit is safe. Per this class's asymmetry, unknown escalates.
	 */
	@Test
	fun `a file whose baseline copy could not be scanned escalates`() {
		val fixture = fixture()
		// Unreadable only while capturing; the analyzer reads it fine, which is exactly the
		// state that leaves a known file with null facts.
		val baseline =
			AnnotationBaseline.capture(fixture.all, roomProfile) { file ->
				if (file == fixture.userDao) null else AnnotationBaseline.readOrNull(file)
			}
		val analyzer = AnnotationImpactAnalyzer(roomProfile, baseline)

		assertThat(analyzer.escalation(listOf(fixture.userDao)))
			.isEqualTo("UserDao.kt: baseline copy could not be scanned")
	}

	/**
	 * A file that is absent now and absent from the baseline never fed a processor, so it
	 * cannot have changed generated output. The deletion branch must not escalate on it.
	 */
	@Test
	fun `deleting a file the baseline never saw does not escalate`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		val neverSeen = File(root, "app/src/main/java/com/example/notes/Absent.kt")

		assertThat(analyzer.escalation(listOf(neverSeen))).isNull()
	}

	/**
	 * Stripping the processor annotations off a file changes what the processor generates
	 * just as much as adding them does - the generated DAO implementation has to go.
	 */
	@Test
	fun `removing the processor annotations from a file escalates`() {
		val fixture = fixture()
		val analyzer = analyzer(fixture)
		// Every Room annotation gone, so the file stops being a processor input entirely.
		fixture.edit(
			fixture.userDao,
			"""
			package com.example.notes

			interface UserDao {
				fun all(): List<User>

				fun insert(user: User)
			}
			""".trimIndent(),
		)

		assertThat(analyzer.escalation(listOf(fixture.userDao)))
			.isEqualTo("UserDao.kt: processor-relevant annotations added or removed")
	}

	/**
	 * An unreadable source at capture time is recorded as known-but-unscannable rather
	 * than skipped, and contributes no anchors - skipping it would make a later edit to it
	 * read as a file the baseline never saw, and silently pass.
	 */
	@Test
	fun `capture records an unreadable source as known with no facts`() {
		val fixture = fixture()

		val baseline =
			AnnotationBaseline.capture(fixture.all, roomProfile) { file ->
				if (file == fixture.userDao) null else AnnotationBaseline.readOrNull(file)
			}

		assertThat(baseline.known(fixture.userDao)).isTrue()
		assertThat(baseline.factsFor(fixture.userDao)).isNull()
		// The other annotated files still contributed their anchors.
		assertThat(baseline.anchorNames).isNotEmpty()
	}

	private companion object {
		val HILT_ACTIVITY =
			"""
			package com.example.notes

			import android.app.Activity
			import android.os.Bundle
			import dagger.hilt.android.AndroidEntryPoint
			import javax.inject.Inject

			@AndroidEntryPoint
			class HiltActivity(
				@Inject val dao: UserDao,
			) : Activity() {
				override fun onCreate(savedInstanceState: Bundle?) {
					super.onCreate(savedInstanceState)
					setTitle("Hilt")
				}
			}
			""".trimIndent()
	}
}

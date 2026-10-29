package com.itsaky.androidide.editor.language.outline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TreeSitterOutlineProviderTest {
	private lateinit var provider: TreeSitterOutlineProvider

	private val javaSource =
		"""
		public class Main {
			private int count;
			private int x, y;
			public Main() {
			}
			public void run(String[] args) {
			}
			interface Inner {
				void call();
			}
			enum Kind {
				LOCAL, REMOTE
			}
		}
		""".trimIndent()

	private val kotlinSource =
		"""
		class Repo(val name: String, tag: String) {
			companion object Factory {
				val EMPTY = Repo("")
			}
			var count = 0
			fun add(item: String): Boolean {
				val tmp = item.length
				fun helper() = tmp
				return helper() > 0
			}
			constructor(name: String, count: Int) : this(name) {
				this.count = count
			}
		}

		interface Greeter {
			fun greet()
		}

		enum class Kind {
			LOCAL, REMOTE
		}
		fun topLevel() {
			val local = 1
		}
		""".trimIndent()

	private val xmlSource =
		"""
		<LinearLayout>
			<TextView android:id="@+id/title" />
			<FrameLayout>
				<Button android:id="@+id/submit" />
			</FrameLayout>
			<View />
		</LinearLayout>
		""".trimIndent()

	@Before
	fun setUp() {
		provider =
			TreeSitterOutlineProvider(
				InstrumentationRegistry.getInstrumentation().targetContext,
			)
	}

	@Test
	fun supportsExactlyTheConfiguredExtensions() {
		assertThat(provider.supports("java")).isTrue()
		assertThat(provider.supports("kt")).isTrue()
		assertThat(provider.supports("kts")).isTrue()
		assertThat(provider.supports("xml")).isTrue()
		assertThat(provider.supports("XML")).isTrue()
		assertThat(provider.supports("json")).isFalse()
		assertThat(provider.supports("gradle")).isFalse()
		assertThat(provider.supports("")).isFalse()
	}

	@Test
	fun javaOutlineHasExpectedStructure() =
		runBlocking<Unit> {
			val roots = provider.outlineOf("java", javaSource)
			assertThat(roots).hasSize(1)
			val main = roots[0]
			assertThat(main.name).isEqualTo("Main")
			assertThat(main.kind).isEqualTo(OutlineSymbolKind.CLASS)
			assertThat(main.children.map { it.name })
				.containsExactly("count", "x", "y", "Main", "run", "Inner", "Kind")
				.inOrder()
			assertThat(main.children.map { it.kind })
				.containsExactly(
					OutlineSymbolKind.FIELD,
					OutlineSymbolKind.FIELD,
					OutlineSymbolKind.FIELD,
					OutlineSymbolKind.CONSTRUCTOR,
					OutlineSymbolKind.METHOD,
					OutlineSymbolKind.INTERFACE,
					OutlineSymbolKind.ENUM,
				).inOrder()
			val run = main.children[4]
			assertThat(run.detail).isEqualTo("(String[] args)")
			val inner = main.children[5]
			assertThat(inner.children.map { it.name }).containsExactly("call")
			val kind = main.children[6]
			assertThat(kind.children.map { it.name }).containsExactly("LOCAL", "REMOTE").inOrder()
			assertThat(kind.children.map { it.kind }.toSet())
				.containsExactly(OutlineSymbolKind.ENUM_MEMBER)
		}

	@Test
	fun columnsAreCharacterColumnsNotBytes() =
		runBlocking<Unit> {
			val roots = provider.outlineOf("java", javaSource)
			val run = roots[0].children.first { it.name == "run" }
			assertThat(run.selectionRange.start.line).isEqualTo(5)
			assertThat(run.selectionRange.start.column).isEqualTo(13)
		}

	@Test
	fun kotlinOutlineHasExpectedStructure() =
		runBlocking<Unit> {
			val roots = provider.outlineOf("kt", kotlinSource)
			assertThat(roots.map { it.name }).containsExactly("Repo", "Greeter", "Kind", "topLevel").inOrder()
			val repo = roots[0]
			assertThat(repo.kind).isEqualTo(OutlineSymbolKind.CLASS)
			val childNames = repo.children.map { it.name }
			assertThat(childNames)
				.containsExactly("name", "Factory", "count", "add", "constructor")
				.inOrder()
			val companion = repo.children[1]
			assertThat(companion.kind).isEqualTo(OutlineSymbolKind.COMPANION)
			assertThat(companion.children.map { it.name }).containsExactly("EMPTY")
			val add = repo.children[3]
			assertThat(add.kind).isEqualTo(OutlineSymbolKind.METHOD)
			assertThat(add.detail).isEqualTo("(item: String)")
			val ctor = repo.children[4]
			assertThat(ctor.kind).isEqualTo(OutlineSymbolKind.CONSTRUCTOR)
			assertThat(add.children).isEmpty()
			val greeter = roots[1]
			assertThat(greeter.kind).isEqualTo(OutlineSymbolKind.INTERFACE)
			assertThat(greeter.children.map { it.name }).containsExactly("greet")
			val kind = roots[2]
			assertThat(kind.kind).isEqualTo(OutlineSymbolKind.ENUM)
			assertThat(kind.children.map { it.name }).containsExactly("LOCAL", "REMOTE").inOrder()
			assertThat(roots[3].children).isEmpty()
		}

	@Test
	fun xmlOutlineNestsElementsAndShowsIds() =
		runBlocking<Unit> {
			val roots = provider.outlineOf("xml", xmlSource)
			assertThat(roots).hasSize(1)
			val root = roots[0]
			assertThat(root.name).isEqualTo("LinearLayout")
			assertThat(root.kind).isEqualTo(OutlineSymbolKind.ELEMENT)
			assertThat(root.detail).isNull()
			assertThat(root.children.map { it.name })
				.containsExactly("TextView", "FrameLayout", "View")
				.inOrder()
			assertThat(root.children[0].detail).isEqualTo("@+id/title")
			assertThat(root.children[1].children.map { it.name }).containsExactly("Button")
			assertThat(root.children[1].children[0].detail).isEqualTo("@+id/submit")
			assertThat(root.children[2].detail).isNull()
		}

	@Test
	fun emptySourceProducesEmptyOutline() =
		runBlocking<Unit> {
			assertThat(provider.outlineOf("java", "")).isEmpty()
		}

	@Test
	fun repeatedCallsAreStable() =
		runBlocking<Unit> {
			val first = provider.outlineOf("java", javaSource)
			repeat(25) {
				assertThat(provider.outlineOf("java", javaSource)).isEqualTo(first)
			}
		}
}

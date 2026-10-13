package org.appdevforall.codeonthego.lsp.java.providers.completion

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.lsp.java.compiler.ClasspathClassNames
import org.appdevforall.codeonthego.lsp.java.compiler.CompileTask
import org.appdevforall.codeonthego.lsp.java.compiler.compilerWithClasspath
import org.appdevforall.codeonthego.lsp.java.refactor.JavacFixture
import org.appdevforall.codeonthego.lsp.models.CompletionItem
import org.appdevforall.codeonthego.lsp.models.CompletionItemKind
import org.appdevforall.codeonthego.lsp.util.DefaultServerSettings
import org.appdevforall.codeonthego.project.GradleModels
import org.appdevforall.codeonthego.project.JavaModels
import org.appdevforall.codeonthego.projects.api.JavaModule
import org.appdevforall.codeonthego.projects.api.ModuleProject
import org.appdevforall.codeonthego.projects.util.BootClasspathProvider
import org.appdevforall.codeonthego.utils.Environment
import io.mockk.every
import io.mockk.mockk
import openjdk.source.util.TreePath
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup.Child
import org.junit.After
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

@RunWith(JUnit4::class)
class ImportCompletionProviderTest {
	companion object {
		@JvmStatic
		@BeforeClass
		fun setUpEnvironment() {
			// JavaCompilerService's static initializer reads it; only a device sets it.
			if (Environment.ANDROID_JAR == null) {
				Environment.ANDROID_JAR = File("android.jar")
			}
		}
	}

	private val fixture = JavacFixture("import java.util.List;\nclass Fixture {}")

	@After
	fun tearDown() = fixture.close()

	private fun module(): ModuleProject =
		JavaModule(
			GradleModels.GradleProject
				.newBuilder()
				.setName("app")
				.setPath(":app")
				.setJavaProject(JavaModels.JavaProject.getDefaultInstance())
				.build(),
		)

	private fun complete(
		importPath: String,
		module: ModuleProject?,
		classpath: ClasspathClassNames?,
		bootClasses: Set<String> = emptySet(),
	): List<CompletionItem> {
		val file: Path = Paths.get("Fixture.java")
		val task = mockk<CompileTask> { every { root(any<Path>()) } returns fixture.root }
		val importTree = TreePath(TreePath(fixture.root), fixture.root.imports.single())
		val compiler = compilerWithClasspath(module, classpath, bootClasses)
		val provider = ImportCompletionProvider(file, 0, compiler, DefaultServerSettings())
		provider.importPath = importPath
		return provider.complete(task, importTree, "", false).items
	}

	private fun List<CompletionItem>.labels() = map { it.ideLabel }

	@Test
	fun `a package only the class index holds is completed`() {
		val items = complete("com.", module(), FakeClasspathPackages(listOf("com.indexed.Widget")))

		assertThat(items.labels()).contains("indexed")
	}

	@Test
	fun `classpath packages and classes are itemised as packages and classes`() {
		val items =
			complete("com.lib.", module(), FakeClasspathPackages(listOf("com.lib.Widget", "com.lib.impl.Engine")))

		assertThat(items.associate { it.ideLabel to it.completionKind })
			.containsExactly("impl", CompletionItemKind.MODULE, "Widget", CompletionItemKind.CLASS)
	}

	@Test
	fun `without a module the boot classpath is still completed`() {
		// Any real JAR stands in for android.jar; kotlin-stdlib is on the test classpath.
		val bootJar =
			File(
				Unit::class.java.protectionDomain.codeSource.location
					.toURI(),
			).path
		BootClasspathProvider.update(listOf(bootJar))
		try {
			val items = complete("kotlin.La", module = null, classpath = null)

			assertThat(items.labels()).contains("Lazy")
		} finally {
			BootClasspathProvider.drop(bootJar)
		}
	}

	@Test
	fun `one completion request queries the classpath children at most once`() {
		val classpath =
			CountingChildrenClasspathPackages(FakeClasspathPackages(listOf("com.lib.Widget", "com.lib.impl.Engine")))

		complete("com.lib.", module(), classpath)

		assertThat(classpath.childrenCalls).isAtMost(1)
	}
}

/** Counts calls to [children] so a test can assert the classpath is queried at most once per request. */
private class CountingChildrenClasspathPackages(
	private val delegate: ClasspathClassNames,
) : ClasspathClassNames by delegate {
	var childrenCalls = 0
		private set

	override fun children(packageName: String): List<Child> {
		childrenCalls++
		return delegate.children(packageName)
	}
}

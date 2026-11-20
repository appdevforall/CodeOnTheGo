package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.platform.analysisMessageBus
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEventListener
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusConnection
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Requirement: an edit confined to one reparseable block invalidates only that declaration, so FIR
 * publishes no module out-of-block event for it, yet the next analysis sees the new body; edits that
 * change a signature, an import or the top-level declarations still publish one.
 */
internal class InBlockInvalidationTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()
	private val outOfBlockEvents = Collections.synchronizedList(mutableListOf<KotlinModuleOutOfBlockModificationEvent>())
	private lateinit var connection: MessageBusConnection

	private val original = "package p\n\nfun f(a: Int): Int {\n    val x = 1\n    return a + x.hashCode()\n}"

	@Before
	fun subscribe() {
		connection = env.project.analysisMessageBus.connect()
		connection.subscribe(
			KotlinModificationEvent.TOPIC,
			KotlinModificationEventListener { event ->
				if (event is KotlinModuleOutOfBlockModificationEvent) outOfBlockEvents.add(event)
			},
		)
	}

	@After
	fun tearDown() {
		connection.disconnect()
		env.ktSymbolIndex.indexWorker.commandIntakeObserver = null
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private fun open(
		name: String,
		content: String,
	): Path {
		createSourceFile(name, content)
		val path = env.sourceRoots.first().resolve(name)
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
		runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
		return path
	}

	private fun edit(
		path: Path,
		content: String,
		version: Int,
	) {
		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, content, content, version, ChangeType.NEW_TEXT, 0, Range.NONE),
		)
		runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
	}

	/** The class id of the type of property [name], or null when it is not a class type. */
	private fun propertyType(
		path: Path,
		name: String,
	): String? =
		env.ktSymbolIndex.withLiveKtFile(path) { live ->
			live.analyzing(AnalysisPriority.DIAGNOSTICS, noopCancelChecker()) { ktFile ->
				val property = PsiTreeUtil.findChildrenOfType(ktFile, KtProperty::class.java).single { it.name == name }
				(property.returnType as? KaClassType)?.classId?.asString()
			}
		}

	private fun diagnosticNames(path: Path): List<String> =
		env.ktSymbolIndex.withLiveKtFile(path) { live ->
			live.analyzing(AnalysisPriority.DIAGNOSTICS, noopCancelChecker()) { ktFile ->
				ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS).map { it.factoryName }
			}
		}!!

	private fun pinnedText(path: Path): String? = env.ktSymbolIndex.withLiveKtFile(path) { live -> live.read { it.text } }

	@Test
	fun `an in-body edit publishes no out-of-block event and analysis sees the new body`() {
		val path = open("InBody.kt", original)
		assertThat(propertyType(path, "x")).isEqualTo("kotlin/Int")
		outOfBlockEvents.clear()

		edit(path, original.replace("val x = 1", "val x = \"s\""), 2)

		assertThat(outOfBlockEvents).isEmpty()
		assertThat(propertyType(path, "x")).isEqualTo("kotlin/String")
	}

	@Test
	fun `an in-body edit that introduces an error is reported by the next diagnostics run`() {
		val path = open("InBodyError.kt", original)
		assertThat(diagnosticNames(path)).isEmpty()

		edit(path, original.replace("val x = 1", "val x: String = 1"), 2)

		assertThat(diagnosticNames(path)).isNotEmpty()
	}

	@Test
	fun `a signature edit publishes an out-of-block event for the module`() {
		val path = open("Signature.kt", original)
		propertyType(path, "x")
		outOfBlockEvents.clear()

		edit(path, original.replace("a: Int", "a: Long"), 2)

		assertThat(outOfBlockEvents).isNotEmpty()
	}

	@Test
	fun `an import edit publishes an out-of-block event`() {
		val path = open("Import.kt", original)
		propertyType(path, "x")
		outOfBlockEvents.clear()

		edit(path, original.replace("package p\n", "package p\nimport kotlin.math.abs\n"), 2)

		assertThat(outOfBlockEvents).isNotEmpty()
	}

	@Test
	fun `a top-level declaration edit publishes an out-of-block event`() {
		val path = open("TopLevel.kt", original)
		propertyType(path, "x")
		outOfBlockEvents.clear()

		edit(path, original + "\nfun g() {}", 2)

		assertThat(outOfBlockEvents).isNotEmpty()
	}

	@Test
	fun `an edit inside a lambda in a property initializer is seen by analysis`() {
		val lambda = "package p\n\nval p = run { 1 }\n"
		val path = open("Lambda.kt", lambda)
		assertThat(propertyType(path, "p")).isEqualTo("kotlin/Int")

		edit(path, lambda.replace("run { 1 }", "run { \"s\" }"), 2)

		assertThat(propertyType(path, "p")).isEqualTo("kotlin/String")
	}

	@Test
	fun `an edit inside a comment in a body is seen and does not throw`() {
		val commented = original.replace("    val x = 1\n", "    // note\n    val x = 1\n")
		val path = open("Comment.kt", commented)
		assertThat(propertyType(path, "x")).isEqualTo("kotlin/Int")
		val edited = commented.replace("// note", "// noted here")

		edit(path, edited, 2)

		assertThat(pinnedText(path)).isEqualTo(edited)
		assertThat(propertyType(path, "x")).isEqualTo("kotlin/Int")
	}

	@Test
	fun `an in-body edit without a local class does not queue a reindex, and one adding a local class does`() {
		val reindexed = LinkedBlockingQueue<KtFile>()
		env.ktSymbolIndex.indexWorker.commandIntakeObserver = { cmd ->
			if (cmd is IndexCommand.IndexModifiedFile) reindexed.add(cmd.ktFile)
		}
		val path = open("LocalClass.kt", original)
		propertyType(path, "x")
		val document = env.ktSymbolIndex.incrementalDocument(path)!!.ktFile
		assertThat(reindexed.poll(5, TimeUnit.SECONDS)).isSameInstanceAs(document)

		edit(path, original.replace("val x = 1", "val x = 2"), 2)
		// Checked before the next edit: both edits keep the same KtFile, so a later command could stand in for this one.
		assertThat(reindexed.poll(500, TimeUnit.MILLISECONDS)).isNull()

		edit(path, original.replace("val x = 1", "class L\n    val x = 2"), 3)

		assertThat(reindexed.poll(5, TimeUnit.SECONDS)).isSameInstanceAs(document)
	}

	@Test
	fun `an in-body edit removing a block's only local class queues a reindex`() {
		val reindexed = LinkedBlockingQueue<KtFile>()
		env.ktSymbolIndex.indexWorker.commandIntakeObserver = { cmd ->
			if (cmd is IndexCommand.IndexModifiedFile) reindexed.add(cmd.ktFile)
		}
		val withLocalClass = original.replace("val x = 1", "class L\n    val x = 1")
		val path = open("RemovedLocalClass.kt", withLocalClass)
		propertyType(path, "x")
		val document = env.ktSymbolIndex.incrementalDocument(path)!!.ktFile
		assertThat(reindexed.poll(5, TimeUnit.SECONDS)).isSameInstanceAs(document)

		edit(path, original, 2)

		assertThat(reindexed.poll(5, TimeUnit.SECONDS)).isSameInstanceAs(document)
	}
}

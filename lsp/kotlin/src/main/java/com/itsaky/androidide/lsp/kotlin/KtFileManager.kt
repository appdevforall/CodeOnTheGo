package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.projects.FileManager
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Manages [KtFile] instances for all open files.
 */
class KtFileManager(
	private val psiFactory: KtPsiFactory,
	private val psiManager: PsiManager,
	private val psiDocumentManager: PsiDocumentManager,
) : FileEventConsumer, AutoCloseable {

	companion object {
		private val logger = LoggerFactory.getLogger(KtFileManager::class.java)
	}

	private val entries = ConcurrentHashMap<Path, ManagedFile>()

	@ConsistentCopyVisibility
	data class ManagedFile @Deprecated("Use ManagedFile.create instead") internal constructor(
		val file: Path,
		val diskKtFile: KtFile,
		@Volatile var inMemoryKtFile: KtFile,
		val document: Document,
		@Volatile var lastModified: Instant,
		@Volatile var isDirty: Boolean,
		@Volatile var analyzeTimestamp: Instant,
	) {

		/**
		 * Analyze this [ManagedFile] contents.
		 *
		 * @param action The analysis action.
		 */
		fun <R> analyze(action: KaSession.(file: KtFile) -> R): R {
			if (diskKtFile === inMemoryKtFile) {
				return analyze(useSiteElement = inMemoryKtFile) { action(inMemoryKtFile) }
			}

			return analyzeCopy(
				useSiteElement = inMemoryKtFile,
				resolutionMode = KaDanglingFileResolutionMode.PREFER_SELF
			) {
				action(inMemoryKtFile)
			}
		}

		fun createInMemoryFileWithContent(psiFactory: KtPsiFactory, content: String): KtFile {
			val inMemoryFile = psiFactory.createFile(file.name, content)
			inMemoryFile.originalFile = diskKtFile
			return inMemoryFile
		}

		companion object {
			@Suppress("DEPRECATION")
			fun create(
				file: Path,
				ktFile: KtFile,
				document: Document,
				inMemoryKtFile: KtFile = ktFile,
				lastModified: Instant = Clock.System.now(),
				isDirty: Boolean = false,
				analyzeTimestamp: Instant = Instant.DISTANT_PAST,
			) =
				ManagedFile(
					file = file,
					diskKtFile = ktFile,
					inMemoryKtFile = inMemoryKtFile,
					document = document,
					lastModified = lastModified,
					isDirty = isDirty,
					analyzeTimestamp = analyzeTimestamp,
				)
		}
	}

	override fun onFileOpened(path: Path, content: String) {
		logger.debug("onFileOpened: {}", path)

		entries[path]?.let { existing ->
			logger.info("File is already opened, updating content")
			updateDocumentContent(existing, content)
			return
		}

		val ktFile = resolveKtFile(path)

		if (ktFile == null) {
			logger.warn("Cannot resolve KtFile for: {}", path)
			return
		}

		val document = getOrCreateDocument(ktFile)
		if (document == null) {
			logger.warn("Cannot obtain Document for: {}", path)
			return
		}

		logger.info("Creating managed file entry")
		val entry = ManagedFile.create(
			file = path,
			ktFile = ktFile,
			document = document,
		)

		entries[path] = entry

		updateDocumentContent(entry, content)
		logger.debug("File opened and managed: {}", path)
	}

	override fun onFileContentChanged(path: Path, content: String) {
		logger.debug("onFileContentChanged: {}", path)
		val entry = entries[path] ?: run {
			logger.debug("Content changed for unmanaged file: {}. Ignoring.", path)
			return
		}

		updateDocumentContent(entry, content)
	}

	override fun onFileSaved(path: Path) {
		val entry = entries[path] ?: return
		entry.isDirty = false

		logger.debug("File saved: {}", path)
	}

	override fun onFileClosed(path: Path) {
		entries.remove(path) ?: return
		logger.debug("File closed: {}", path)
	}

	fun getOpenFile(path: Path): ManagedFile? {
		val managed = entries[path]
		if (managed != null) {
			return managed
		}

		val activeDocument = FileManager.getActiveDocument(path)
		if (activeDocument != null) {
			// document is active, but we were not notified
			// open it now
			onFileOpened(path, activeDocument.content)
			return entries[path]
		}

		return null
	}

	fun allOpenFiles(): Collection<ManagedFile> =
		entries.values.toList()

	fun clearAnalyzeTimestampOf(file: Path) {
		val managed = getOpenFile(file) ?: return
		managed.analyzeTimestamp = Instant.DISTANT_PAST
	}

	private fun resolveKtFile(path: Path): KtFile? {
		val vfs = VirtualFileManager.getInstance()
			.getFileSystem(StandardFileSystems.FILE_PROTOCOL)

		val virtualFile = vfs.refreshAndFindFileByPath(path.pathString)
			?: return null

		val psiFile = psiManager.findFile(virtualFile)

		return psiFile as? KtFile
	}

	private fun getOrCreateDocument(ktFile: KtFile): Document? {
		return psiDocumentManager.getDocument(ktFile)
	}

	private fun updateDocumentContent(entry: ManagedFile, content: String) {
		logger.info("Updating doc content for {}", entry.file)

		val normalized = content.replace("\r", "")
		if (entry.inMemoryKtFile.text == normalized) return

		entry.inMemoryKtFile = entry.createInMemoryFileWithContent(psiFactory, content)
		entry.lastModified = Clock.System.now()
		entry.isDirty = true
	}

	override fun close() {
		entries.clear()
	}
}
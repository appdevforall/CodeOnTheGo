package com.itsaky.androidide.lsp.kotlin.compiler.index

import androidx.annotation.VisibleForTesting
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.ChangedPsiRangeUtil
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.FileElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.StampedLock

/**
 * The single [KtFile] of one open Kotlin document, kept across versions by applying each new text
 * to it in place.
 *
 * @property ktFile The instance every version of the document is applied to.
 */
internal class IncrementalKtDocument(
	val ktFile: KtFile,
	appliedVersion: Int,
) {
	/** The document version [ktFile]'s text reflects. */
	@Volatile
	var appliedVersion: Int = appliedVersion
		private set

	/**
	 * Pins hold a read stamp; an edit takes the write stamp with tryWriteLock only.
	 *
	 * A [StampedLock] rather than a ReentrantReadWriteLock because a pin is joined and released on
	 * different threads, and stamps are not bound to the thread that took them.
	 */
	val editLock = StampedLock()

	/** Replaces the in-place reparse in [apply]; set only by tests to force an outcome. */
	@VisibleForTesting
	@Volatile
	internal var reparseOverride: ((CharSequence) -> ReparseOutcome)? = null

	/**
	 * Advances [appliedVersion] to [version] if [text] is already [ktFile]'s text, and returns whether
	 * it did.
	 *
	 * Caller holds the [editLock] write stamp, which excludes every other edit, so this needs neither
	 * project.write nor the analysis barrier: a version bump that changes nothing preempts nothing.
	 */
	fun advanceIfUnchanged(
		text: CharSequence,
		version: Int,
	): Boolean {
		if (!(ktFile.node as FileElement).textMatches(text)) return false
		appliedVersion = version
		return true
	}

	/**
	 * Applies [text], the document at [version], to [ktFile] in place.
	 *
	 * Caller holds project.write and runs inside runWriteAction. On any outcome but
	 * [ReparseOutcome.Failed], [appliedVersion] becomes [version]; on [ReparseOutcome.Failed] the tree
	 * may be partly edited and the caller must stop using [ktFile].
	 */
	fun apply(
		text: CharSequence,
		version: Int,
	): ReparseOutcome {
		val outcome =
			try {
				reparseOverride?.invoke(text) ?: reparseInPlace(text)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				ReparseOutcome.Failed("in-place reparse threw ${e.javaClass.name}", e)
			}
		if (outcome !is ReparseOutcome.Failed) {
			appliedVersion = version
		}
		return outcome
	}

	private fun reparseInPlace(text: CharSequence): ReparseOutcome {
		val fileNode = ktFile.node as FileElement
		val range = ChangedPsiRangeUtil.getChangedPsiRange(ktFile, fileNode, text) ?: return ReparseOutcome.Unchanged
		val viewProvider = ktFile.viewProvider
		val result =
			BlockSupportImpl.reparse(ktFile, fileNode, range, text, EmptyProgressIndicator(), viewProvider.contents)
		val oldRoot = result.oldRoot
		// The diff below moves the new children into oldRoot, so its old contents are only readable now.
		val replacedHadClassOrObject =
			oldRoot !is FileElement && PsiTreeUtil.findChildOfType(oldRoot.psi, KtClassOrObject::class.java) != null
		// The contents go first, as in IntelliJ's own commit (document, then reparse): applying the PSI
		// change checks the tree against the view provider's contents and reports a mismatch as an error
		// carrying the whole file. doActualPsiChange does not update the contents itself. On a failure
		// below the caller drops this KtFile, so contents ahead of the tree are never read.
		(viewProvider.virtualFile as LightVirtualFile).setContent(null, text, false)
		syncDocument(text)
		// PomModelImpl refuses to change physical PSI outside a command. CoreCommandProcessor swallows
		// whatever the runnable throws, so the text check below is the only reliable failure signal.
		CommandProcessor.getInstance().executeCommand(
			ktFile.project,
			{ result.log.doActualPsiChange(ktFile) },
			null,
			null,
		)
		if (!(ktFile.node as FileElement).textMatches(text)) {
			return ReparseOutcome.Failed("tree text does not match the document after the reparse", null)
		}

		if (oldRoot is FileElement) return ReparseOutcome.WholeFile
		// A reparseable node outside the Kotlin PSI (KDoc) has no KtElement to report; the whole file is
		// the conservative answer.
		return (oldRoot.psi as? KtElement)?.let { ReparseOutcome.InBlock(it, replacedHadClassOrObject) } ?: ReparseOutcome.WholeFile
	}

	/**
	 * The view provider caches a Document once anything asks for one (the PSI change itself does), and
	 * that Document, not the light file, is what the provider's contents and offset-to-line mapping read.
	 * Nothing syncs it from the PSI, so it is set here; when none is cached, the one created below
	 * already reads the new light-file content.
	 */
	private fun syncDocument(text: CharSequence) {
		val document = ktFile.viewProvider.document ?: return
		if (!StringUtil.equals(document.immutableCharSequence, text)) {
			document.setText(text)
		}
	}
}

/** What [IncrementalKtDocument.apply] did to the document's [KtFile]. */
internal sealed interface ReparseOutcome {
	/** The text already matched the tree; nothing changed. */
	data object Unchanged : ReparseOutcome

	/**
	 * Only [reparsed], a reparseable node still attached to the file, was replaced.
	 *
	 * @property reparsed The replaced node, now holding the new children.
	 * @property replacedHadClassOrObject Whether the children it held before the edit included a class
	 *   or object declaration.
	 */
	data class InBlock(
		val reparsed: KtElement,
		val replacedHadClassOrObject: Boolean,
	) : ReparseOutcome

	/** The file element itself was reparsed, though the [KtFile] instance is kept. */
	data object WholeFile : ReparseOutcome

	/** The edit could not be applied in place; the caller falls back to a full parse. */
	data class Failed(
		val reason: String,
		val cause: Throwable?,
	) : ReparseOutcome
}

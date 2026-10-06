package org.appdevforall.codeonthego.lsp.kotlin.actions

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.ActionItem
import org.appdevforall.codeonthego.actions.EditorActionItem
import org.appdevforall.codeonthego.actions.get
import org.appdevforall.codeonthego.actions.hasRequiredData
import org.appdevforall.codeonthego.actions.markInvisible
import org.appdevforall.codeonthego.actions.requireContext
import org.appdevforall.codeonthego.actions.requireFile
import org.appdevforall.codeonthego.lsp.api.ILanguageClient
import org.appdevforall.codeonthego.lsp.kotlin.KotlinLanguageServer
import org.appdevforall.codeonthego.lsp.kotlin.diagnostic.DiagnosticAction
import org.appdevforall.codeonthego.lsp.kotlin.diagnostic.KotlinDiagnosticExtra
import org.appdevforall.codeonthego.lsp.kotlin.diagnostic.asAction
import org.appdevforall.codeonthego.lsp.models.DiagnosticItem
import org.appdevforall.codeonthego.lsp.models.DiagnosticsInSelection
import org.appdevforall.codeonthego.utils.DocumentUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

abstract class BaseKotlinCodeAction : EditorActionItem {
	override var visible: Boolean = true
	override var enabled: Boolean = true
	override var icon: Drawable? = null
	override var requiresUIThread: Boolean = false
	override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

	@get:StringRes
	protected abstract var titleTextRes: Int

	protected val logger: Logger = LoggerFactory.getLogger(javaClass)

	override fun prepare(data: ActionData) {
		super.prepare(data)
		if (!data.hasRequiredData(
				Context::class.java,
				KotlinLanguageServer::class.java,
				File::class.java,
			)
		) {
			markInvisible()
			return
		}

		val context = data.requireContext()
		val file = data.requireFile()
		val isKtFile = DocumentUtils.isKotlinFile(file.toPath())

		if (titleTextRes != -1) {
			label = context.getString(titleTextRes)
		}

		visible = isKtFile
		enabled = isKtFile
	}

	protected val ActionData.languageClient: ILanguageClient?
		get() =
			get<KotlinLanguageServer>()
				?.client

	internal inline fun <reified T : DiagnosticAction> DiagnosticItem.ktExtra(): KotlinDiagnosticExtra<T>? =
		(extra as? KotlinDiagnosticExtra<*>)?.asAction<T>()

	/**
	 * Find the first [DiagnosticItem] in the current selection matching the given [predicate].
	 * Prefers [DiagnosticsInSelection] (any matching diagnostic in the selected area); falls back
	 * to the at-selection-start [DiagnosticItem] when no container is present.
	 */
	protected inline fun ActionData.findFirstDiagnosticItem(predicate: (DiagnosticItem) -> Boolean): DiagnosticItem? =
		get<DiagnosticsInSelection>()
			?.let { return it.diagnostics.firstOrNull(predicate) }
			?: get<DiagnosticItem>()?.takeIf(predicate)

	/**
	 * Find the first in-selection [DiagnosticItem] whose extra carries a [T] action, paired with the
	 * typed [KotlinDiagnosticExtra]. Carries the type evidence through so callers don't re-extract.
	 */
	internal inline fun <reified T : DiagnosticAction> ActionData.findDiagnosticExtra(): Pair<DiagnosticItem, KotlinDiagnosticExtra<T>>? {
		val item = findFirstDiagnosticItem { it.ktExtra<T>() != null } ?: return null
		return item to item.ktExtra<T>()!!
	}
}

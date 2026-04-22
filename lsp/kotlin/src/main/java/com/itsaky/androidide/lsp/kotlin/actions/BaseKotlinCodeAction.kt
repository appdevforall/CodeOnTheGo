package com.itsaky.androidide.lsp.kotlin.actions

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.EditorActionItem
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.hasRequiredData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.utils.DocumentUtils
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

	protected val logger = LoggerFactory.getLogger(BaseKotlinCodeAction::class.java)

	override fun prepare(data: ActionData) {
		super.prepare(data)
		if (!data.hasRequiredData(
				Context::class.java,
				KotlinLanguageServer::class.java,
				File::class.java
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
		get() = get<KotlinLanguageServer>()
			?.client
}

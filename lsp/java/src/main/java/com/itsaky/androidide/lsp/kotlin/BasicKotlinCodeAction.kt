package com.itsaky.androidide.lsp.kotlin

import AndroidKotlinLanguageServer
import android.content.Context
import android.graphics.drawable.Drawable
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.EditorActionItem
import com.itsaky.androidide.actions.hasRequiredData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.java.R
import com.itsaky.androidide.lsp.java.rewrite.Rewrite
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.DocumentUtils
import com.itsaky.androidide.utils.ILogger
import com.itsaky.androidide.utils.flashError
import java.io.File

abstract class BasicKotlinCodeAction : EditorActionItem {

    override var visible: Boolean = true
    override var enabled: Boolean = true
    override var icon: Drawable? = null
    override var requiresUIThread: Boolean = false
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

    protected abstract val titleTextRes: Int

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (
            !data.hasRequiredData(Context::class.java, AndroidKotlinLanguageServer::class.java, File::class.java)
        ) {
            markInvisible()
            return
        }

        if (titleTextRes != -1) {
            label = data[Context::class.java]!!.getString(titleTextRes)
        }

        val file = data.requireFile()
        visible = DocumentUtils.isKotlinFile(file.toPath())
        enabled = visible
    }

    fun performCodeAction(data: ActionData, result: Rewrite) {
        val compiler = data.requireCompiler()

        val actions =
            try {
                result.asCodeActions(compiler, label)
            } catch (e: Exception) {
                flashError(e.cause?.message ?: e.message)
                ILogger.ROOT.error(e.cause?.message ?: e.message, e)
                return
            }

        if (actions == null) {
            onPerformCodeActionFailed(data)
            return
        }

        getLanguageClient()?.performCodeAction(actions)
    }

    protected open fun onPerformCodeActionFailed(data: ActionData) {
        flashError(R.string.msg_codeaction_failed)
    }

    private fun requireLanguageServer(): AndroidKotlinLanguageServer {
        return ILanguageServerRegistry.getDefault().getServer(AndroidKotlinLanguageServer.SERVER_ID)
            as AndroidKotlinLanguageServer
    }

    private fun getLanguageClient(): ILanguageClient? {
        return requireLanguageServer().client
    }

    protected fun ActionData.requireCompiler(): KotlinCompilerService {
        val module = IProjectManager.getInstance().findModuleForFile(requireFile(), false)
        requireNotNull(module) {
            "Cannot get compiler instance. Unable to find module for file: ${requireFile().name}"
        }
        return KotlinCompilerProvider.get(module)
    }
}

package com.itsaky.androidide.actions.agent

import android.content.Context
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.BaseEditorAction
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.agent.actions.EditorAiActionDispatcher
import com.itsaky.androidide.agent.actions.SelectedCodeContext
import com.itsaky.androidide.agent.actions.SelectionAiPromptFactory
import com.itsaky.androidide.agent.repository.BackendAvailability
import com.itsaky.androidide.agent.repository.getBackendAvailability
import com.itsaky.androidide.agent.repository.Util.getCurrentBackend
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.FeatureFlags
import java.io.File

class ExplainSelectionAction(
	private val context: Context,
	override val order: Int
) : BaseEditorAction() {

    companion object {
        const val ID = "ide.editor.agent.text.explain_selection"
        private const val MAX_SELECTION_CHARS = 8000
    }

    override val id: String = ID
    override fun retrieveTooltipTag(isReadOnlyContext: Boolean) = TooltipTag.EDITOR_TOOLBAR_AI

    init {
        label = context.getString(R.string.action_explain_selection)

        icon = ContextCompat.getDrawable(context, R.drawable.ic_auto_awesome)
    }

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (!visible) return

        if (!FeatureFlags.isExperimentsEnabled) {
            markInvisible()
            return
        }

        val editor = getEditor(data)
        if (editor == null || editor.file == null || editor.isReadOnlyContext) {
            markInvisible()
            return
        }

        val target = getTextTarget(data)
        visible = target != null
        enabled = visible && target?.hasSelection() == true

        if (!enabled) return
    }

    override suspend fun execAction(data: ActionData): Boolean {
        if (!this.enabled) return false

        when (val availability = getBackendAvailability(context)) {
            is BackendAvailability.Available -> Unit
            is BackendAvailability.Unavailable -> {
                Toast.makeText(context, availability.messageRes, Toast.LENGTH_SHORT).show()
                return false
            }
        }

        val target = getTextTarget(data) ?: return false
        val rawSelectedText = target.getSelectedText() ?: return false
        if (rawSelectedText.isBlank()) return false

        val clippedText = getClippedText(rawSelectedText)

        val file = data.get(File::class.java)
        val range = data.get(Range::class.java)
        val projectRoot = getCurrentProject()
        val relativePath = getRelativePath(file, projectRoot)

        val selectionContext = SelectedCodeContext(
					selectedText = clippedText,
					fileName = file?.name,
					filePath = relativePath ?: file?.name,
					fileExtension = file?.extension,
					lineStart = range?.start?.line?.plus(1),
					lineEnd = range?.end?.line?.plus(1),
					selectionLength = rawSelectedText.length
				)

        val prompt = SelectionAiPromptFactory.build(selectionContext, getCurrentBackend())
        return EditorAiActionDispatcher.dispatch(data, prompt, rawSelectedText)
    }

    private fun getClippedText(rawSelectedText: String): String {
        if (rawSelectedText.length > MAX_SELECTION_CHARS) {
            return rawSelectedText.take(MAX_SELECTION_CHARS) + "\n... [truncated due to size]"
        }

        return rawSelectedText
    }

    private fun getRelativePath(file: File?, projectRoot: File?): String? {
        if (file == null || projectRoot == null) return null

        return runCatching {
            file.canonicalFile.relativeTo(projectRoot).path
        }.getOrNull()
    }

    private fun getCurrentProject(): File? {
        return runCatching {
            IProjectManager.getInstance().projectDir.canonicalFile
        }.getOrNull()
    }
}

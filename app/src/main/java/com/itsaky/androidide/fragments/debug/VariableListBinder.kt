package com.itsaky.androidide.fragments.debug

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import com.itsaky.androidide.databinding.DebuggerSetVariableValueBinding
import com.itsaky.androidide.databinding.DebuggerVariableItemBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.DEBUG_OUTPUT_VARIABLES
import com.itsaky.androidide.lsp.debug.model.VariableDescriptor
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.isSystemInDarkMode
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import io.github.dingyi222666.view.treeview.TreeNode
import io.github.dingyi222666.view.treeview.TreeNodeEventListener
import io.github.dingyi222666.view.treeview.TreeView
import io.github.dingyi222666.view.treeview.TreeViewBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class VariableListBinder(
    private val coroutineScope: CoroutineScope,
    private val viewModel: DebuggerViewModel
) : TreeViewBinder<ResolvableVariable<*>>() {

    private var treeIndent = 0

    companion object {
        private val logger = LoggerFactory.getLogger(VariableListBinder::class.java)

        /* Visual limit for the list (RecyclerView).
         * If larger than this, we truncate with "..." to avoid scroll lag.
         */
        private const val MAX_PREVIEW_LENGTH = 50

        /* Threshold to switch rendering strategy in the editor dialog.
         * If exceeded, we disable line wrapping (Word Wrap) and HW acceleration to prevent ANRs.
         */
        private const val HUGE_TEXT_THRESHOLD = 5000
    }

    /**
     * Helper class to hold the binding and the async job specifically for this view.
     * Stored in view.tag to survive recycling.
     */
    private data class ViewHolderState(
        val binding: DebuggerVariableItemBinding,
        var currentJob: Job? = null
    )

    override fun createView(parent: ViewGroup, viewType: Int): View {
        val inflater = LayoutInflater.from(parent.context)
        val binding = DebuggerVariableItemBinding.inflate(inflater, parent, false)

        binding.root.tag = ViewHolderState(binding)

        return binding.root
    }

    override fun getItemViewType(node: TreeNode<ResolvableVariable<*>>): Int = 0

    override fun bindView(
        holder: TreeView.ViewHolder,
        node: TreeNode<ResolvableVariable<*>>,
        listener: TreeNodeEventListener<ResolvableVariable<*>>
    ) {
        val state = holder.itemView.tag as? ViewHolderState
            ?: ViewHolderState(DebuggerVariableItemBinding.bind(holder.itemView)).also {
                holder.itemView.tag = it
            }

        val itemBinding = state.binding

        state.currentJob?.cancel()

        val context = itemBinding.root.context

        if (treeIndent == 0) {
            treeIndent = context.resources.getDimensionPixelSize(
                R.dimen.content_padding_double
            )
        }

        itemBinding.apply {
            root.setPaddingRelative(
                /* start = */ node.depth * treeIndent,
                /* top = */ root.paddingTop,
                /* end = */ root.paddingEnd,
                /* bottom = */ root.paddingBottom
            )

            chevron.rotation = if (node.isExpanded) 90f else 0f

            if (node.data?.isResolved != true) {
                label.text = context.getString(R.string.debugger_status_resolving)
            }
        }

        val data = node.data ?: run {
            logger.error("No data set to node: {}", node)
            return
        }

        state.currentJob = coroutineScope.launch(Dispatchers.IO) {
            val descriptor = data.resolve()

            ensureActive()

            val strValue = data.resolvedValue()?.toString()
                ?: context.getString(R.string.debugger_value_unavailable)
            val previewValue = if (strValue.length > MAX_PREVIEW_LENGTH)
                strValue.take(MAX_PREVIEW_LENGTH) + "..." else strValue

            withContext(Dispatchers.Main) {
                ensureActive()

                itemBinding.apply {
                    if (descriptor == null) {
                        logger.error("Unable to resolve node: {}", data)
                        label.text = context.getString(R.string.debugger_value_error)
                        return@apply
                    }

                    val ic = descriptor.icon(context)?.let { ContextCompat.getDrawable(context, it) }

                    // noinspection SetTextI18n
                    label.text = "${descriptor.name}: ${descriptor.typeName} = $previewValue"
                    icon.setImageDrawable(ic ?: CircleCharDrawable(descriptor.kind.name.first(), true))

                    chevron.visibility = if (descriptor.kind == VariableKind.PRIMITIVE) View.INVISIBLE else View.VISIBLE

                    showSetValueDialogOnClick(itemBinding, data, descriptor, strValue)

                    root.setOnLongClickListener {
                        TooltipManager.showIdeCategoryTooltip(context, root, DEBUG_OUTPUT_VARIABLES)
                        true
                    }
                }
            }
        }
    }

    private fun showSetValueDialogOnClick(
        itemBinding: DebuggerVariableItemBinding,
        variable: ResolvableVariable<*>,
        descriptor: VariableDescriptor,
        currentValue: String
    ) {
        val context = itemBinding.root.context
        itemBinding.root.setOnClickListener {
            if (!descriptor.isMutable) {
                // variable is immutable
                flashError(context.getString(R.string.debugger_error_immutable_variable, descriptor.name))
                return@setOnClickListener
            }

            val labelText = itemBinding.label.text?.toString()

            if (labelText.isNullOrBlank()) return@setOnClickListener

            val hasValidValue = currentValue.isNotBlank() &&
                    currentValue != context.getString(R.string.debugger_value_unavailable) &&
                    currentValue != context.getString(R.string.debugger_value_error) &&
                    currentValue != context.getString(R.string.debugger_value_null)

            if (!hasValidValue) return@setOnClickListener

            showSetValueDialog(context, variable, descriptor, currentValue)
        }
    }

    private fun showSetValueDialog(
        context: Context,
        variable: ResolvableVariable<*>,
        descriptor: VariableDescriptor,
        currentValue: String
    ) {
        val title = context.getString(
            R.string.debugger_variable_dialog_title,
            descriptor.name,
            descriptor.typeName
        )

        val inflater = LayoutInflater.from(context)
        val dialogBinding = DebuggerSetVariableValueBinding.inflate(inflater)
        val isHugeText = currentValue.length > HUGE_TEXT_THRESHOLD

        setupInputEditor(dialogBinding.input, isHugeText)
        dialogBinding.loadingIndicator.visibility = View.VISIBLE
        dialogBinding.inputLayout.visibility = View.INVISIBLE

        val dialog = DialogUtils.newMaterialDialogBuilder(context)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(context.getString(R.string.debugger_dialog_button_set), null)
            .setNegativeButton(context.getString(android.R.string.cancel), null)
            .setCancelable(true)
            .create()

        dialog.setOnShowListener { d ->
            (d as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                handleSaveAction(dialogBinding, variable, d)
            }
        }

        dialog.show()

        loadTextAsync(dialogBinding, dialog, currentValue, isHugeText)
    }

    /**
     * Configures the EditText properties to avoid ANRs with large text.
     */
    private fun setupInputEditor(input: android.widget.EditText, isHugeText: Boolean) {
        val baseInputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        input.apply {
            if (isHugeText) {
                inputType = baseInputType or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                isSingleLine = true
                setHorizontallyScrolling(true)
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            } else {
                inputType = baseInputType
                isSingleLine = false
                setHorizontallyScrolling(false)
            }
        }
    }

    /**
     * Handles value saving logic, validation, and visual feedback.
     */
    private fun handleSaveAction(
        dialogBinding: DebuggerSetVariableValueBinding,
        variable: ResolvableVariable<*>,
        dialog: AlertDialog
    ) {
        val newValue = dialogBinding.input.text?.toString() ?: ""

        coroutineScope.launch(Dispatchers.IO) {
            val isSet = variable.setValue(newValue)
            withContext(Dispatchers.Main) {
                if (isSet) {
                    dialogBinding.inputLayout.error = null
                    dialog.dismiss()
                    viewModel.refreshState()
                } else {
                    dialogBinding.inputLayout.error =
                        dialogBinding.root.context.getString(R.string.debugger_variable_value_invalid)
                }
            }
        }
    }

    /**
     * Calculates text layout in background and assigns it when ready.
     */
    private fun loadTextAsync(
        dialogBinding: DebuggerSetVariableValueBinding,
        dialog: AlertDialog,
        text: String,
        isHugeText: Boolean
    ) {
        dialogBinding.input.post {
            if (!dialog.isShowing) return@post

            val params = TextViewCompat.getTextMetricsParams(dialogBinding.input)

            coroutineScope.launch(Dispatchers.Default) {
                try {
                    val precomputedText = PrecomputedTextCompat.create(text, params)

                    withContext(Dispatchers.Main) {
                        if (dialog.isShowing) {
                            TextViewCompat.setPrecomputedText(dialogBinding.input, precomputedText)
                            finalizeDialogUI(dialogBinding, text, isHugeText)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to precompute text", e)
                    withContext(Dispatchers.Main) {
                        if (dialog.isShowing) {
                            val truncationMsg = dialogBinding.root.context.getString(R.string.debugger_variable_truncated)
                            val safeText = if (text.length > HUGE_TEXT_THRESHOLD) {
                                text.take(HUGE_TEXT_THRESHOLD) + "\n\n[$truncationMsg]"
                            } else { text }
                            dialogBinding.input.setText(safeText)
                            finalizeDialogUI(dialogBinding, text, isHugeText)
                        }
                    }
                }
            }
        }
    }

    private fun finalizeDialogUI(
        dialogBinding: DebuggerSetVariableValueBinding,
        currentValue: String,
        isHugeText: Boolean
    ) {
        dialogBinding.loadingIndicator.visibility = View.GONE
        dialogBinding.inputLayout.visibility = View.VISIBLE
        dialogBinding.input.requestFocus()

        if (currentValue.isEmpty()) return

        if (!isHugeText) {
            dialogBinding.input.selectAll()
            return
        }
        dialogBinding.input.setSelection(0)
    }
}

private fun VariableDescriptor.icon(context: Context): Int? = when (kind) {
    VariableKind.PRIMITIVE -> R.drawable.ic_db_primitive
    VariableKind.REFERENCE -> R.drawable.ic_dbg_value
    VariableKind.ARRAYLIKE -> when (context.isSystemInDarkMode()) {
        true -> R.drawable.ic_db_array_dark
        false -> R.drawable.ic_db_array
    }
    else -> null
}
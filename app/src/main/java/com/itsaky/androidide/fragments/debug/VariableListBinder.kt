package com.itsaky.androidide.fragments.debug

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.itsaky.androidide.databinding.DebuggerSetVariableValueBinding
import com.itsaky.androidide.databinding.DebuggerVariableItemBinding
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
    }

    override fun createView(parent: ViewGroup, viewType: Int): View {
        val inflater = LayoutInflater.from(parent.context)
        val binding = DebuggerVariableItemBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun getItemViewType(node: TreeNode<ResolvableVariable<*>>): Int = 0

    override fun bindView(
        holder: TreeView.ViewHolder,
        node: TreeNode<ResolvableVariable<*>>,
        listener: TreeNodeEventListener<ResolvableVariable<*>>
    ) {
        val binding = DebuggerVariableItemBinding.bind(holder.itemView)
        val context = binding.root.context

        if (treeIndent == 0) {
            treeIndent = context.resources.getDimensionPixelSize(
                R.dimen.content_padding_double
            )
        }

        binding.apply {
            root.setPaddingRelative(
                /* start = */ node.depth * treeIndent,
                /* top = */ root.paddingTop,
                /* end = */ root.paddingEnd,
                /* bottom = */ root.paddingBottom
            )

            chevron.rotation = if (node.isExpanded) 90f else 0f
        }

        Log.d("VariableListBinder", "bindView: node.data=${node.data}")
        if (node.data?.isResolved != true) {
            binding.label.text = context.getString(R.string.debugger_status_resolving)
        }

        val data = node.data ?: run {
            logger.error("No data set to node: {}", node)
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            val descriptor = data.resolve()
            val strValue = data.resolvedValue()?.toString()
                ?: context.getString(R.string.debugger_value_unavailable)

            withContext(Dispatchers.Main) {
                binding.apply {
                    if (descriptor == null) {
                        logger.error("Unable to resolve node: {}", data)
                        label.text = context.getString(R.string.debugger_value_error)
                        return@apply
                    }

                    val ic = descriptor.icon(context)?.let { ContextCompat.getDrawable(context, it) }

                    // noinspection SetTextI18n
                    label.text =
                        "${descriptor.name}: ${descriptor.typeName} = $strValue"
                    icon.setImageDrawable(ic ?: CircleCharDrawable(descriptor.kind.name.first(), true))

                    chevron.visibility = if (descriptor.kind == VariableKind.PRIMITIVE) View.INVISIBLE else View.VISIBLE

                    showSetValueDialogOnLongClick(binding, data, descriptor, strValue)
                }
            }
        }
    }

    private fun showSetValueDialogOnLongClick(
        binding: DebuggerVariableItemBinding,
        variable: ResolvableVariable<*>,
        descriptor: VariableDescriptor,
        currentValue: String
    ) {
        val context = binding.root.context
        binding.root.setOnLongClickListener {
            if (!descriptor.isMutable) {
                // variable is immutable
                flashError(context.getString(R.string.debugger_error_immutable_variable, descriptor.name))
                return@setOnLongClickListener false
            }

            val labelText = binding.label.text?.toString()

            if (labelText.isNullOrBlank()) return@setOnLongClickListener false

            val hasValidValue = currentValue.isNotBlank() &&
                    currentValue != context.getString(R.string.debugger_value_unavailable) &&
                    currentValue != context.getString(R.string.debugger_value_error) &&
                    currentValue != context.getString(R.string.debugger_value_null)

            if (!hasValidValue) return@setOnLongClickListener false

            showSetValueDialog(context, variable, descriptor, currentValue)
            true
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
        val binding = DebuggerSetVariableValueBinding.inflate(inflater)
        binding.input.setText(currentValue)
        if (currentValue.isNotEmpty()) {
            binding.input.selectAll()
        }

        DialogUtils.newMaterialDialogBuilder(context)
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(context.getString(R.string.debugger_dialog_button_set), null)
            .setNegativeButton(context.getString(android.R.string.cancel), null)
            .setCancelable(true)
            .create()
            .apply {
                setOnShowListener { dialog ->
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val inputLayout = binding.inputLayout
                        val input = binding.input
                        val newValue = input.text?.toString()?.takeIf(String::isNotBlank) ?: run {
                            binding.inputLayout.error = context.getString(R.string.debugger_variable_value_invalid)
                            return@setOnClickListener
                        }

                        coroutineScope.launch {
                            val isSet = variable.setValue(newValue)
                            if (isSet) {
                                inputLayout.error = null
                                dialog.dismiss()
                                viewModel.refreshVariables()
                            } else {
                                inputLayout.error =
                                    context.getString(R.string.debugger_variable_value_invalid)
                            }
                        }
                    }
                }
            }
            .show()
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
package com.itsaky.androidide.fragments.debug

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.itsaky.androidide.databinding.DebuggerVariableItemBinding
import com.itsaky.androidide.lsp.debug.model.VariableDescriptor
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.isSystemInDarkMode
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
        if (treeIndent == 0) {
            treeIndent = binding.root.context.resources.getDimensionPixelSize(
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
            binding.label.text = binding.root.context.getString(R.string.debugger_status_resolving)
        }

        val data = node.data ?: run {
            logger.error("No data set to node: {}", node)
            return
        }

        coroutineScope.launch {
            val descriptor = data.resolve()
            withContext(Dispatchers.Main) {
                binding.apply {
                    if (descriptor == null) {
                        logger.error("Unable to resolve node: {}", data)
                        label.text = "<error>"
                        return@apply
                    }

                    val ic = descriptor.icon(root.context)?.let { ContextCompat.getDrawable(root.context, it) }

                    // noinspection SetTextI18n
                    label.text =
                        "${descriptor.name}: ${descriptor.typeName} = ${data.resolvedValue()}"
                    icon.setImageDrawable(ic ?: CircleCharDrawable(descriptor.kind.name.first(), true))

                    chevron.visibility = if (descriptor.kind == VariableKind.PRIMITIVE) View.INVISIBLE else View.VISIBLE
                }
            }
        }
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

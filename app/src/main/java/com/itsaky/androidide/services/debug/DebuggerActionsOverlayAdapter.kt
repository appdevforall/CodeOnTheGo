package com.itsaky.androidide.services.debug

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.editor.databinding.LayoutPopupMenuItemBinding

/**
 * @author Akash Yadav
 */
class DebuggerActionsOverlayAdapter(
    actions: List<ActionItem>,
): RecyclerView.Adapter<DebuggerActionsOverlayAdapter.VH>() {

    private val actions = actions.sortedBy { action -> action.order }
    private val actionsRegister = ActionsRegistry.getInstance() as DefaultActionsRegistry

    class VH(
        val binding: LayoutPopupMenuItemBinding
    ): RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = LayoutPopupMenuItemBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun getItemCount() = actions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val binding = holder.binding
        val action = actions[position]
        val data = ActionData.create(binding.root.context)

        action.prepare(data)

        binding.root.icon = action.icon
        TooltipCompat.setTooltipText(binding.root, action.label)

        binding.root.setOnClickListener {
            actionsRegister.executeAction(action, data)
        }
    }
}
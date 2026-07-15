/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.databinding.LayoutMainActionItemBinding

/**
 * Adapter for the actions available on the main screen.
 *
 * @author Akash Yadav
 */
class MainActionsListAdapter
@JvmOverloads
constructor(
    val actions: List<ActionItem> = emptyList(),
    private val onClick: ((ActionItem, View) -> Unit)? = null,
    private val onLongClick: ((ActionItem, View) -> Boolean)? = null,
) :
    RecyclerView.Adapter<MainActionsListAdapter.VH>() {
    inner class VH(val binding: LayoutMainActionItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutMainActionItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = actions.size

    fun getAction(index: Int) = actions[index]

    override fun onBindViewHolder(holder: VH, position: Int) {
        val action = getAction(index = position)
        val binding = holder.binding

        binding.root.apply {
            val originalText = action.label
            text = originalText
            isEnabled = action.enabled
            icon = action.icon
            contentDescription = originalText
            setOnClickListener {
                onClick?.invoke(action, it)
            }
            setOnLongClickListener {
                onLongClick?.invoke(action, it) ?: true
            }
        }
        (binding.root as? MaterialButton)?.findViewById<View>(com.google.android.material.R.id.icon)
            ?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (binding.root is ViewGroup) {
            for (i in 0 until (binding.root as ViewGroup).childCount) {
                val child = (binding.root as ViewGroup).getChildAt(i)
                if (child is android.widget.ImageView) {
                    child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    break
                }
            }
        }
    }
}

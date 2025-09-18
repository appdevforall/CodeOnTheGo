/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package com.itsaky.androidide.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutSymbolItemBinding
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.models.Symbol
import com.itsaky.androidide.utils.resolveAttr
import io.github.rosemoe.sora.widget.SelectionMovement
import java.util.Objects

class SymbolInputAdapter @JvmOverloads constructor(
    private var editor: IDEEditor,
    symbols: Collection<Symbol>? = null
) : RecyclerView.Adapter<SymbolInputAdapter.VH?>() {
    private val symbols: MutableList<Symbol> = ArrayList()

    init {
        this.updateItems(symbols)
    }

    private fun updateItems(symbols: Collection<Symbol>?) {
        if (symbols == null) {
            return
        }

        this.symbols.clear()
        this.symbols.addAll(symbols)
        this.symbols.removeIf { obj: Symbol? -> Objects.isNull(obj) }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh(editor: IDEEditor?, newSymbols: Collection<Symbol>?) {
        this.editor = Objects.requireNonNull<IDEEditor>(editor)

        if (this.symbols == newSymbols) {
            // no need to update symbols
            return
        }

        updateItems(newSymbols)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, itemType: Int): VH {
        return VH(
            LayoutSymbolItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val symbol = symbols[position]
        holder.binding.symbol.text = symbol.label
        holder.binding.symbol.setTextColor(
            holder.binding.symbol.context.resolveAttr(R.attr.colorOnSurface)
        )

        val description = symbol.description

        if (!description.isEmpty()) {
            TooltipCompat.setTooltipText(holder.binding.symbol, description)
        }

        holder.binding.symbol.setOnClickListener { view: View? ->
            insertSymbol(
                symbol.commit,
                symbol.offset
            )
        }

        holder.binding.symbol.setOnLongClickListener { view: View? ->
            TooltipManager.showTooltip(
                context = editor.context,
                anchorView = editor,
                tag = holder.binding.symbol,
            )
            true
        }
    }

    override fun getItemCount(): Int {
        return symbols.size
    }

    fun insertSymbol(text: String, selectionOffset: Int) {
        if (selectionOffset < 0 || selectionOffset > text.length) {
            return
        }

        val controller = editor.snippetController
        if ("\t" == text && controller.isInSnippet()) {
            controller.shiftToNextTabStop()
            return
        }

        if ("\t" == text) {
            editor.indentOrCommitTab()
            return
        }

        val cur = editor.text.getCursor()
        if (cur.isSelected) {
            editor
                .text
                .delete(
                    cur.leftLine,
                    cur.leftColumn,
                    cur.rightLine,
                    cur.rightColumn
                )
            editor.notifyIMEExternalCursorChange()
        }

        if (cur.leftColumn < editor.text
                .getColumnCount(cur.leftLine) && text.length == 1 && text[0] == editor.text
                .charAt(cur.leftLine, cur.leftColumn) && pairs.contains(text[0])
        ) {
            editor.moveSelection(SelectionMovement.RIGHT)
        } else {
            editor.commitText(text)
            if (selectionOffset != text.length) {
                editor.setSelection(
                    cur.rightLine, cur.rightColumn - (text.length - selectionOffset)
                )
            }
        }
    }

    class VH(var binding: LayoutSymbolItemBinding) : RecyclerView.ViewHolder(
        binding.getRoot()
    )

    companion object {
        private val pairs = ArrayList<Char?>()

        init {
            pairs.add('}')
            pairs.add(')')
            pairs.add(']')
            pairs.add('"')
            pairs.add('\'')
            pairs.add('>')
        }
    }
}

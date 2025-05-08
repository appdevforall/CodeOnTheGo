package com.itsaky.androidide.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.common.databinding.LayoutSimpleIconTextBinding
import com.itsaky.androidide.databinding.FragmentDebuggerBinding
import com.itsaky.androidide.lsp.debug.model.LocalVariable

private class SimpleVariable(
    override val name: String,
    override val type: String,
    private var value: String,
) : LocalVariable {

    override fun getValue(): String = value

    override fun setValue(value: String) {
        this.value = value
    }
}

/**
 * @author Akash Yadav
 */
class DebuggerFragment :
    FragmentWithBinding<FragmentDebuggerBinding>(FragmentDebuggerBinding::inflate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sampleThreads = arrayOf(
            "Thread #1",
            "Thread #2",
            "Thread #3",
            "Thread #4",
            "Thread #5",
            "Thread #6",
            "Thread #7",
            "Thread #8",
            "Thread #9",
            "Thread #10",
        )

        val sampleVariables = arrayOf(
            SimpleVariable(
                name = "someInt",
                type = "int",
                value = "42"
            ),
            SimpleVariable(
                name = "someString",
                type = "String",
                value = "\"Some string\""
            ),
            SimpleVariable(
                name = "someRef",
                type = "com.itsaky.androidide.Something",
                value = "com.itsaky.androidide.Something@1a2b3c4d"
            ),
            SimpleVariable(
                name = "someArray",
                type = "String[]",
                value = "[\"some\", \"string\"]"
            ),
        )

        binding.threadLayoutSelector.spinnerText.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                sampleThreads
            )
        )

        binding.variablesList.layoutManager = LinearLayoutManager(requireContext())
        binding.variablesList.adapter = VariableListAdapter(sampleVariables)
    }
}

class VariableListAdapter(
    private val variables: Array<out LocalVariable>
) : RecyclerView.Adapter<VariableListAdapter.VH>() {

    class VH(
        val binding: LayoutSimpleIconTextBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding =
            LayoutSimpleIconTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int {
        return variables.size
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val binding = holder.binding
        val variable = variables[position]
        binding.text.text = "${variable.name} = ${variable.type} ${variable.getValue()}"
    }
}

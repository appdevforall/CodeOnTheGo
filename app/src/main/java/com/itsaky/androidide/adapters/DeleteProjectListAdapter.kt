package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.DeleteProjectsItemBinding
import com.itsaky.androidide.models.Checkable
import org.appdevforall.codeonthego.layouteditor.ProjectFile

class DeleteProjectListAdapter(
    private var projects: List<Checkable<ProjectFile>>,
    private val onSelectionChange: (Boolean) -> Unit,
    private val onCheckboxLongPress: () -> Boolean
) : RecyclerView.Adapter<DeleteProjectListAdapter.ProjectViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val binding =
            DeleteProjectsItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProjectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(projects[position])
    }

    override fun getItemCount(): Int = projects.size

    fun getSelectedProjects(): List<ProjectFile> = projects.filter { it.isChecked }.map { it.data }

    fun updateProjects(newProjects: List<Checkable<ProjectFile>>) {
        projects = newProjects
        notifyDataSetChanged()
    }

    private fun hasSelection(): Boolean = projects.any { it.isChecked }

    private fun renderDate(binding: DeleteProjectsItemBinding, project: ProjectFile) {
        binding.projectDate.text = project.renderDateText(binding.root.context)
    }

    inner class ProjectViewHolder(private val binding: DeleteProjectsItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Checkable<ProjectFile>) {
            val project = item.data

            binding.projectName.text = project.name
            renderDate(binding, project)
            binding.icon.text = project.name.take(2).uppercase()

            binding.checkbox.visibility = View.VISIBLE
            binding.checkbox.isChecked = item.isChecked

            binding.root.setOnClickListener {
                item.isChecked = !item.isChecked
                binding.checkbox.isChecked = item.isChecked
                onSelectionChange(hasSelection())
            }

            binding.checkbox.setOnClickListener {
                binding.root.performClick()
            }

            binding.checkbox.setOnLongClickListener {
                onCheckboxLongPress()
            }
        }
    }
}


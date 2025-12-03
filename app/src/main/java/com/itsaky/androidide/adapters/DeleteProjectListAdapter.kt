package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.DeleteProjectsItemBinding
import com.itsaky.androidide.databinding.SavedRecentProjectItemBinding
import com.itsaky.androidide.utils.formatDate
import com.itsaky.androidide.utils.getLastModifiedTime
import org.appdevforall.codeonthego.layouteditor.ProjectFile

class DeleteProjectListAdapter(
    private var projects: List<ProjectFile>,
    private val onSelectionChange: (Boolean) -> Unit,
    private val onCheckboxLongPress: () -> Boolean
) : RecyclerView.Adapter<DeleteProjectListAdapter.ProjectViewHolder>() {

    private val selectedProjects = mutableSetOf<ProjectFile>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val binding =
            DeleteProjectsItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProjectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(projects[position])
    }

    override fun getItemCount(): Int = projects.size

    fun getSelectedProjects(): List<ProjectFile> = selectedProjects.toList()

    fun updateProjects(newProjects: List<ProjectFile>) {
        projects = newProjects
        notifyDataSetChanged()
    }

    fun renderDate(binding: DeleteProjectsItemBinding, project: ProjectFile) {
        val showModified = project.createdAt == project.lastModified
        val ctx = binding.root.context

        val label = if (showModified)
            ctx.getString(R.string.date_created_label)
        else
            ctx.getString(R.string.date_modified_label)
            val renderDate = if (showModified) project.createdAt else project.lastModified

        binding.projectDate.text = binding.root.context.getString(
            R.string.date,
            label,
            formatDate(renderDate ?: "")
        )
        return
    }

    inner class ProjectViewHolder(private val binding: DeleteProjectsItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(project: ProjectFile) {
            binding.projectName.text = project.name
            renderDate(binding, project)
            binding.icon.text = project.name.take(2).uppercase()

            binding.checkbox.visibility = View.VISIBLE
            binding.checkbox.isChecked = selectedProjects.contains(project)

            binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedProjects.add(project) else selectedProjects.remove(
                    project
                )
                onSelectionChange(selectedProjects.isNotEmpty())
            }

            binding.checkbox.setOnLongClickListener {
                onCheckboxLongPress()
            }
        }
    }
}


package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.DeleteProjectsItemBinding
import com.itsvks.layouteditor.ProjectFile
import java.text.SimpleDateFormat
import java.util.Locale

class DeleteProjectListAdapter(
    private var projects: List<ProjectFile>,
    private val onSelectionChange: (Boolean) -> Unit
) : RecyclerView.Adapter<DeleteProjectListAdapter.ProjectViewHolder>() {

    private val selectedProjects = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val binding =
            DeleteProjectsItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProjectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(projects[position])
    }

    override fun getItemCount(): Int = projects.size

    fun getSelectedProjects(): List<String> = selectedProjects.toList()

    fun updateProjects(newProjects: List<ProjectFile>) {
        projects = newProjects
        notifyDataSetChanged()
    }

    inner class ProjectViewHolder(private val binding: DeleteProjectsItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(project: ProjectFile) {
            binding.projectName.text = project.name
            binding.projectDate.text = formatDate(project.date ?: "")
            binding.icon.text = project.name.take(2).uppercase()

            binding.checkbox.visibility = View.VISIBLE
            binding.checkbox.isChecked = selectedProjects.contains(project.name)

            binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedProjects.add(project.name) else selectedProjects.remove(
                    project.name
                )
                onSelectionChange(selectedProjects.isNotEmpty())
            }
        }
    }

    private fun formatDate(dateString: String): String {
        return try {
            val inputFormat =
                SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.getDefault())
            val date = inputFormat.parse(dateString)
            val day = SimpleDateFormat("d", Locale.ENGLISH).format(date).toInt()
            val suffix = when {
                day in 11..13 -> "th"
                day % 10 == 1 -> "st"
                day % 10 == 2 -> "nd"
                day % 10 == 3 -> "rd"
                else -> "th"
            }
            val outputFormat = SimpleDateFormat("d'$suffix', MMMM yyyy", Locale.getDefault())
            outputFormat.format(date)
        } catch (e: Exception) {
            dateString.take(5)
        }
    }
}

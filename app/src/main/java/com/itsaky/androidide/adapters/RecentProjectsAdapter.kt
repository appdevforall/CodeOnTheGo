package com.itsaky.androidide.adapters

import android.content.Context
import android.text.Editable
import android.text.TextWatcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.FileUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.SavedRecentProjectItemBinding
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.utils.FlashType
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashMessage
import com.itsaky.androidide.utils.flashSuccess
import com.itsvks.layouteditor.ProjectFile
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding
import com.itsvks.layouteditor.utils.FileUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class RecentProjectsAdapter(
    private var projects: List<ProjectFile>,
    private val onProjectClick: (File) -> Unit,
    private val onOpenFileFromFolderClick: () -> Unit,
    private val onRemoveProjectClick: (ProjectFile) -> Unit,
    private val onFileRenamed: (RenamedFile) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VIEW_TYPE_PROJECT = 0
        const val VIEW_TYPE_OPEN_FOLDER = 1
    }

    override fun getItemCount(): Int = projects.size + if (projects.isNotEmpty()) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (position < projects.size) VIEW_TYPE_PROJECT else VIEW_TYPE_OPEN_FOLDER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_PROJECT -> {
                val binding = SavedRecentProjectItemBinding.inflate(inflater, parent, false)
                ProjectViewHolder(binding)
            }

            else -> {
                val view =
                    inflater.inflate(R.layout.saved_project_open_folder_layout, parent, false)
                OpenFolderViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ProjectViewHolder -> holder.bind(projects[position])
            is OpenFolderViewHolder -> holder.bind()
        }
    }

    fun updateProjects(newProjects: List<ProjectFile>) {
        projects = newProjects
        notifyDataSetChanged()
    }

    inner class ProjectViewHolder(private val binding: SavedRecentProjectItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(project: ProjectFile) {
            binding.projectName.text = project.name
            binding.projectDate.text = formatDate(project.date ?: "")
            binding.icon.text = project.name
                .split(" ")
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .take(2)
                .joinToString("")

            TooltipCompat.setTooltipText(
                binding.menu,
                binding.root.context.getString(R.string.options)
            )
            TooltipCompat.setTooltipText(binding.root, project.name)
            binding.root.animation =
                AnimationUtils.loadAnimation(binding.root.context, R.anim.project_list_animation)

            binding.root.setOnClickListener {
                onProjectClick(File(project.path))
            }
            binding.menu.setOnClickListener {
                showPopupMenu(it, adapterPosition)
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
                SimpleDateFormat("d'$suffix', MMMM yyyy", Locale.getDefault()).format(date)
            } catch (e: Exception) {
                dateString.take(5)
            }
        }
    }

    inner class OpenFolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            itemView.visibility = if (projects.isEmpty()) View.GONE else View.VISIBLE
            itemView.setOnClickListener { onOpenFileFromFolderClick() }
        }
    }

    private fun showPopupMenu(view: View, position: Int) {
        PopupMenu(view.context, view).apply {
            inflate(R.menu.menu_recent_projects)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_delete -> {
                        showDeleteDialog(view.context, position)
                        true
                    }

                    R.id.menu_rename -> {
                        promptRenameProject(view, position)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
    }

    private fun showDeleteDialog(context: Context, position: Int) {
        val project = projects[position]
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_project)
            .setMessage(R.string.msg_delete_project)
            .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.yes) { _, _ ->
                onRemoveProjectClick(project)
                executeAsync({ FileUtils.delete(project.path) }) {
                    val deleted = it ?: false
                    if (!deleted) {
                        return@executeAsync
                    }
                }
            }
            .show()
    }

    private fun promptRenameProject(view: View, position: Int) {
        val context = view.context
        val project = projects[position]
        val oldName = projects[position].name
        val builder = MaterialAlertDialogBuilder(context).setTitle(R.string.rename_project)

        val binding = TextinputlayoutBinding.inflate(LayoutInflater.from(context))
        binding.textinputEdittext.setText(project.name)
        binding.textinputLayout.hint = context.getString(R.string.msg_new_project_name)
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        builder.setView(binding.root, padding, padding, padding, padding)

        builder.setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
        builder.setPositiveButton(R.string.rename) { _, _ ->
            val newName = binding.textinputEdittext.text.toString()
            val newPath = project.path.substringBeforeLast("/") + "/" + newName
            try {
                project.rename(newPath)
                flashSuccess(R.string.renamed)
                onFileRenamed(RenamedFile(oldName, newName, newPath))
                notifyItemChanged(position)
            } catch (e: Exception) {
                flashError(R.string.rename_failed)
            }
        }

        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        binding.textinputEdittext.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateProjectName(binding.textinputLayout, s.toString(), project.name, dialog)
            }
        })

        validateProjectName(
            binding.textinputLayout,
            binding.textinputEdittext.text.toString(),
            project.name,
            dialog
        )
    }

    private fun validateProjectName(
        inputLayout: TextInputLayout, newName: String, currentName: String, dialog: AlertDialog
    ) {
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        when {
            newName.isEmpty() -> {
                inputLayout.error = dialog.context.getString(R.string.msg_cannnot_empty)
                positiveButton.isEnabled = false
            }

            else -> {
                inputLayout.error = null
                positiveButton.isEnabled = true
            }
        }
    }

    data class RenamedFile(val oldName: String, val newName: String, val newPath: String)
}

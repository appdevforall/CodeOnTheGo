package com.itsaky.androidide.adapters

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.PopupWindow
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.FileUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.SavedRecentProjectItemBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.DELETE_PROJECT
import com.itsaky.androidide.idetooltips.TooltipTag.DELETE_PROJECT_DIALOG
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_RECENT_RENAME
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_RECENT_TOP
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_RENAME_DIALOG
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.utils.applyLongPressRecursively
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.formatDate
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import org.appdevforall.codeonthego.layouteditor.databinding.TextinputlayoutBinding
import org.slf4j.LoggerFactory
import java.io.File

class RecentProjectsAdapter(
    private var projects: List<ProjectFile>,
    private val onProjectClick: (File) -> Unit,
    private val onRemoveProjectClick: (ProjectFile) -> Unit,
    private val onFileRenamed: (RenamedFile) -> Unit,
) : RecyclerView.Adapter<RecentProjectsAdapter.ProjectViewHolder>() {

	private var projectOptionsPopup: PopupWindow? = null

    private companion object {
		private val logger = LoggerFactory.getLogger(RecentProjectsAdapter::class.java)
        const val VIEW_TYPE_PROJECT = 0
        const val VIEW_TYPE_OPEN_FOLDER = 1
    }

    override fun getItemCount(): Int = projects.size

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val binding = SavedRecentProjectItemBinding.inflate(inflater, parent, false)
		return ProjectViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
		holder.bind(projects[position], position)
	}

    fun updateProjects(newProjects: List<ProjectFile>) {
        projects = newProjects

		// noinspection NotifyDataSetChanged
        notifyDataSetChanged()
    }

    fun renderDate(binding: SavedRecentProjectItemBinding, project: ProjectFile) {
      val showModified = project.createdAt != project.lastModified
      val renderDate = if (showModified) project.lastModified else project.createdAt
      val ctx = binding.root.context

      val label = if (showModified)
        ctx.getString(R.string.date_modified_label)
      else
        ctx.getString(R.string.date_created_label)

      binding.projectDate.text = binding.root.context.getString(
        R.string.date,
        label,
        formatDate(renderDate ?: "")
      )
    }

    inner class ProjectViewHolder(private val binding: SavedRecentProjectItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(project: ProjectFile, position: Int) {
            binding.projectName.text = project.name

            renderDate(binding, project)

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
            binding.root.setOnLongClickListener {
				TooltipManager.showIdeCategoryTooltip(
					binding.root.context,
					binding.root,
					PROJECT_RECENT_TOP
				)
				true
			}
            binding.menu.setOnClickListener { view ->
                showPopupMenu(view, project, position)
			}
		}
	}

    private fun showPopupMenu(view: View, project: ProjectFile, position: Int) {
        val inflater = LayoutInflater.from(view.context)

		// noinspection InflateParams
        val popupView = inflater.inflate(R.layout.custom_popup_menu, null)

		projectOptionsPopup?.dismiss()
		projectOptionsPopup =
			PopupWindow(
				popupView,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				true,
			)

		val popupWindow = projectOptionsPopup!!

        val renameItem = popupView.findViewById<View>(R.id.menu_rename)
        val deleteItem = popupView.findViewById<View>(R.id.menu_delete)

        renameItem.setOnClickListener {
            promptRenameProject(view, project, position)
            popupWindow.dismiss()
        }

        renameItem.setOnLongClickListener {
            popupWindow.dismiss()
            TooltipManager.showIdeCategoryTooltip(
                context = view.context,
                anchorView = view,
                tag = PROJECT_RECENT_RENAME
            )
            true
        }

        deleteItem.setOnClickListener {
            showDeleteDialog(view.context, project)
            popupWindow.dismiss()
        }

        deleteItem.setOnLongClickListener {
            popupWindow.dismiss()
            TooltipManager.showIdeCategoryTooltip(
                context = view.context,
                anchorView = view,
                tag = DELETE_PROJECT
            )
            true
        }

        popupWindow.showAsDropDown(view, 0, 0)
    }

    private fun showDeleteDialog(context: Context, project: ProjectFile) {
        val dialog = MaterialAlertDialogBuilder(context)
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
            .create()


        val contentView = dialog.window?.decorView

        dialog.setOnShowListener {
            contentView?.applyLongPressRecursively {
                TooltipManager.showIdeCategoryTooltip(
                    context = context,
                    anchorView = contentView,
                    tag = DELETE_PROJECT_DIALOG
                )
                true
            }
        }

        dialog.show()
    }

    private fun promptRenameProject(view: View, project: ProjectFile, position: Int) {
        val context = view.context
        val oldName = project.name
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
				logger.error("Failed to rename project", e)
                flashError(R.string.rename_failed)
            }
        }

        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val contentView = dialog.window?.decorView

        dialog.setOnShowListener {
            contentView?.applyLongPressRecursively {
                TooltipManager.showIdeCategoryTooltip(
                    context = context,
                    anchorView = contentView,
                    tag = PROJECT_RENAME_DIALOG
                )
                true
            }
        }

        dialog.show()

        binding.textinputEdittext.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateProjectName(binding.textinputLayout, s.toString(), dialog)
            }
        })

        validateProjectName(
            binding.textinputLayout,
            binding.textinputEdittext.text.toString(),
            dialog
        )
    }

    private fun validateProjectName(
        inputLayout: TextInputLayout,
		newName: String,
		dialog: AlertDialog
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

package com.itsaky.androidide.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.itsaky.androidide.databinding.LayoutProjectInfoSheetBinding
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.utils.formatDate
import com.termux.shared.interact.ShareUtils.copyTextToClipboard

import org.appdevforall.codeonthego.layouteditor.ProjectFile

class ProjectInfoBottomSheet(
    private val project: ProjectFile,
    private val recentProject: RecentProject?,
    private val details: ProjectDetails
) : BottomSheetDialogFragment() {

    private var _binding: LayoutProjectInfoSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = LayoutProjectInfoSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindGeneral()
        bindStructure()
        bindBuildSetup()
        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    // -----------------------------
    // GENERAL
    // -----------------------------
    private fun bindGeneral() {
        val unknown = getString(R.string.unknown)
        binding.infoName.setLabel(getString(R.string.project_info_name))
        binding.infoName.setValue(project.name)

        binding.infoLocation.setLabel(getString(R.string.project_info_path))
        binding.infoLocation.setValue(project.path)
        binding.infoLocation.setOnClickListener { copyToClipboard(project.path) }

        binding.infoTemplate.setLabel(getString(R.string.project_info_template))
        binding.infoTemplate.setValue(recentProject?.templateName ?: unknown)

        binding.infoCreatedAt.setLabel(getString(R.string.date_created_label))
        binding.infoCreatedAt.setValue(formatDate(project.createdAt ?: unknown))

        binding.infoModifiedAt.setLabel(getString(R.string.date_modified_label))
        binding.infoModifiedAt.setValue(formatDate(project.lastModified ?: unknown))
    }

    // -----------------------------
    // STRUCTURE
    // -----------------------------
    private fun bindStructure() {
        binding.infoSize.setLabel(getString(R.string.project_info_size))
        binding.infoSize.setValue(details.sizeFormatted)

        binding.infoFilesCount.setLabel(getString(R.string.project_info_files_count))
        binding.infoFilesCount.setValue(details.numberOfFiles.toString())
    }

    // -----------------------------
    // BUILD SETUP
    // -----------------------------
    private fun bindBuildSetup() {
        binding.infoLanguage.setLabel(getString(R.string.wizard_language))
        binding.infoLanguage.setValue(recentProject?.language ?: getString(R.string.unknown))

        binding.infoGradleVersion.setLabel(getString(R.string.project_info_gradle_v))
        binding.infoGradleVersion.setValue(details.gradleVersion)

        binding.infoKotlinVersion.setLabel(getString(R.string.project_info_kotlin_v))
        binding.infoKotlinVersion.setValue(details.kotlinVersion)

        binding.infoJavaVersion.setLabel(getString(R.string.project_info_java_v))
        binding.infoJavaVersion.setValue(details.javaVersion)
    }

    // -----------------------------
    // HELPERS
    // -----------------------------

    private fun copyToClipboard(value: String) {
        copyTextToClipboard(context, value)
        Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }
}

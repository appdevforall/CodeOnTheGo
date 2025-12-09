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
        binding.infoName.setLabelAndValue(
            getString(R.string.project_info_name),
            project.name
        )

        binding.infoLocation.setLabelAndValue(
            getString(R.string.project_info_path),
            project.path
				)
        binding.infoLocation.setOnClickListener { copyToClipboard(project.path) }

        binding.infoTemplate.setLabelAndValue(
            getString(R.string.project_info_template),
            recentProject?.templateName ?: unknown
				)

        binding.infoCreatedAt.setLabelAndValue(
            getString(R.string.date_created_label),
            formatDate(project.createdAt ?: unknown)
				)

        binding.infoModifiedAt.setLabelAndValue(
            getString(R.string.date_modified_label),
            formatDate(project.lastModified ?: unknown)
				)
    }

    // -----------------------------
    // STRUCTURE
    // -----------------------------
    private fun bindStructure() {
        binding.infoSize.setLabelAndValue(
            getString(R.string.project_info_size),
            details.sizeFormatted
				)

        binding.infoFilesCount.setLabelAndValue(
            getString(R.string.project_info_files_count),
            details.numberOfFiles.toString()
				)
    }

    // -----------------------------
    // BUILD SETUP
    // -----------------------------
    private fun bindBuildSetup() {
        binding.infoLanguage.setLabelAndValue(
            getString(R.string.wizard_language),
            recentProject?.language ?: getString(R.string.unknown)
				)

        binding.infoGradleVersion.setLabelAndValue(
            getString(R.string.project_info_gradle_v),
            details.gradleVersion
				)

        binding.infoKotlinVersion.setLabelAndValue(
            getString(R.string.project_info_kotlin_v),
            details.kotlinVersion
				)

        binding.infoJavaVersion.setLabelAndValue(
            getString(R.string.project_info_java_v),
            details.javaVersion
				)
    }

    // -----------------------------
    // HELPERS
    // -----------------------------

    private fun copyToClipboard(value: String) {
        copyTextToClipboard(context, value)
        Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }
}

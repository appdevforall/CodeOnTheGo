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
import com.itsaky.androidide.utils.ProjectDetails
import com.itsaky.androidide.utils.formatDate
import com.itsaky.androidide.utils.loadProjectDetails
import com.itsaky.androidide.utils.viewLifecycleScope
import com.termux.shared.interact.ShareUtils.copyTextToClipboard
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.layouteditor.ProjectFile

class ProjectInfoBottomSheet : BottomSheetDialogFragment() {
    companion object {
        fun newInstance(project: ProjectFile, recent: RecentProject?): ProjectInfoBottomSheet {
            val args = Bundle()
            args.putString("name", project.name)
            args.putString("path", project.path)
            args.putString("created", project.createdAt)
            args.putString("modified", project.lastModified)

            args.putString("template", recent?.templateName)
            args.putString("lang", recent?.language)

            val fragment = ProjectInfoBottomSheet()
            fragment.arguments = args
            return fragment
        }
    }

    private var _binding: LayoutProjectInfoSheetBinding? = null
    private val binding get() = _binding!!

    private val pName by lazy { arguments?.getString("name") ?: "" }
    private val pPath by lazy { arguments?.getString("path") ?: "" }
    private val pCreated by lazy { arguments?.getString("created") }
    private val pModified by lazy { arguments?.getString("modified") }

    private val pTemplate by lazy { arguments?.getString("template") }
    private val pLang by lazy { arguments?.getString("lang") }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = LayoutProjectInfoSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindGeneral()

        setLoadingState(true)

        viewLifecycleScope.launch {
            val details = loadProjectDetails(pPath, requireContext())

            if (isAdded && _binding != null) {
                bindStructure(details)
                bindBuildSetup(details)
                setLoadingState(false)
            }
        }

        binding.btnClose.setOnClickListener { dismiss() }
    }

    // -----------------------------
    // GENERAL
    // -----------------------------
    private fun bindGeneral() {
        val unknown = getString(R.string.unknown)

        binding.infoName.setLabelAndValue(
            getString(R.string.project_info_name),
            pName
        )

        binding.infoLocation.setLabelAndValue(
            getString(R.string.project_info_path),
            pPath
        )
        binding.infoLocation.setOnClickListener { copyToClipboard(pPath) }

        binding.infoTemplate.setLabelAndValue(
            getString(R.string.project_info_template),
            pTemplate ?: unknown
        )

        binding.infoCreatedAt.setLabelAndValue(
            getString(R.string.date_created_label),
            formatDate(pCreated ?: unknown)
        )

        binding.infoModifiedAt.setLabelAndValue(
            getString(R.string.date_modified_label),
            formatDate(pModified ?: unknown)
        )
    }

    // -----------------------------
    // STRUCTURE
    // -----------------------------
    private fun bindStructure(details: ProjectDetails) {
        binding.infoSize.setLabelAndValue(
            getString(R.string.project_info_size), details.sizeFormatted
        )
        binding.infoFilesCount.setLabelAndValue(
            getString(R.string.project_info_files_count), details.numberOfFiles.toString()
        )
    }

    // -----------------------------
    // BUILD SETUP
    // -----------------------------
    private fun bindBuildSetup(details: ProjectDetails) {
        val unknown = getString(R.string.unknown)

        binding.infoLanguage.setLabelAndValue(
            getString(R.string.wizard_language),
            pLang ?: unknown
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

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            binding.progressHeavyData.visibility = View.VISIBLE
            binding.containerHeavyData.visibility = View.GONE
        } else {
            binding.progressHeavyData.visibility = View.GONE

            binding.containerHeavyData.apply {
                alpha = 0f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
        }
    }

    private fun copyToClipboard(value: String) {
        copyTextToClipboard(context, value)
        Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
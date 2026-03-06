package com.itsaky.androidide.fragments.git

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.DialogGitDiffBinding
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import kotlinx.coroutines.launch
import java.io.File

class GitDiffViewerDialog : DialogFragment() {

    private val viewModel: GitBottomSheetViewModel by activityViewModels()

    private var filePath: String = ""

    private var _binding: DialogGitDiffBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_AndroidIDE)
        filePath = arguments?.getString(ARG_FILE_PATH) ?: getString(R.string.diff_viewer)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogGitDiffBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.diffTitle.text = filePath
        binding.diffText.text = getString(R.string.diff_loading)

        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val repo = viewModel.currentRepository
            val diff = if (repo != null && repo.rootDir.exists()) {
                val file = File(repo.rootDir, filePath)
                repo.getDiff(file)
            } else {
                null
            } ?: getString(R.string.unable_to_load_diff)

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (_binding != null) {
                    binding.diffText.text = applyDiffFormatting(diff)
                }
            }
        }
        
        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyDiffFormatting(diff: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val lines = diff.split("\n")
        
        // Find the index of the first diff chunk (starts with @@)
        val firstChunkIndex = lines.indexOfFirst { it.startsWith("@@") }
        val startIndex = if (firstChunkIndex != -1) firstChunkIndex else 0
        
        val context = requireContext()
        val colorAdd = ContextCompat.getColor(context, R.color.git_diff_add_text)
        val bgAdd = ContextCompat.getColor(context, R.color.git_diff_add_bg)
        val colorDel = ContextCompat.getColor(context, R.color.git_diff_del_text)
        val bgDel = ContextCompat.getColor(context, R.color.git_diff_del_bg)
        val colorHeader = ContextCompat.getColor(context, R.color.git_diff_header_text)

        for (i in startIndex until lines.size) {
            val line = lines[i]
            val startIdx = builder.length
            when {
                line.startsWith("+") && !line.startsWith("+++") -> {
                    val formattedLine = line.replaceFirst("+", "+    ")
                    builder.append(formattedLine).append("\n")
                    val endIdx = builder.length
                    
                    builder.setSpan(ForegroundColorSpan(colorAdd), startIdx, endIdx, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(FullWidthBackgroundColorSpan(bgAdd), startIdx, endIdx, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("-") && !line.startsWith("---") -> {
                    val formattedLine = line.replaceFirst("-", "-    ")
                    builder.append(formattedLine).append("\n")
                    val endIdx = builder.length
                    
                    builder.setSpan(ForegroundColorSpan(colorDel), startIdx, endIdx, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(FullWidthBackgroundColorSpan(bgDel), startIdx, endIdx, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("@@") -> {
                    builder.append(line).append("\n")
                    val endIdx = builder.length
                    
                    builder.setSpan(ForegroundColorSpan(colorHeader), startIdx, endIdx, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    val formattedLine = if (line.startsWith(" ")) "    $line" else line
                    builder.append(formattedLine).append("\n")
                }
            }
        }
        
        return builder
    }

    private class FullWidthBackgroundColorSpan(private val color: Int) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint,
            left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lineNumber: Int
        ) {
            val oldColor = paint.color
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = oldColor
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    companion object {
        private const val ARG_FILE_PATH = "arg_file_path"

        fun newInstance(filePath: String): GitDiffViewerDialog {
            return GitDiffViewerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, filePath)
                }
            }
        }
    }
}

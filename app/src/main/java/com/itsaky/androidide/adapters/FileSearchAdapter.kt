package com.itsaky.androidide.adapters

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.LayoutFileSearchItemBinding
import com.itsaky.androidide.models.FileExtension
import com.itsaky.androidide.utils.FileMatch
import java.io.File

class FileSearchAdapter(
	private val onMatchClick: (FileMatch) -> Unit,
) : ListAdapter<FileMatch, FileSearchAdapter.ViewHolder>(DIFF) {
	class ViewHolder(
		val binding: LayoutFileSearchItemBinding,
	) : RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): ViewHolder {
		val holder = ViewHolder(LayoutFileSearchItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
		holder.binding.root.setOnClickListener {
			val position = holder.bindingAdapterPosition
			if (position != RecyclerView.NO_POSITION) {
				onMatchClick(getItem(position))
			}
		}
		return holder
	}

	override fun onBindViewHolder(
		holder: ViewHolder,
		position: Int,
	) {
		val match = getItem(position)
		val path = match.relativePath
		val nameStart = path.lastIndexOf('/') + 1

		holder.binding.icon.setImageResource(FileExtension.Factory.forFile(File(path), false).icon)
		holder.binding.name.text = highlight(path, nameStart, path.length, match.highlights)
		holder.binding.parent.isVisible = nameStart > 0
		holder.binding.parent.text = highlight(path, 0, (nameStart - 1).coerceAtLeast(0), match.highlights)
	}

	private fun highlight(
		path: String,
		start: Int,
		end: Int,
		highlights: List<Int>,
	): CharSequence {
		val text = SpannableString(path.substring(start, end))
		for (index in highlights) {
			if (index !in start until end) {
				continue
			}
			val offset = index - start
			text.setSpan(StyleSpan(Typeface.BOLD), offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		return text
	}

	private companion object {
		val DIFF =
			object : DiffUtil.ItemCallback<FileMatch>() {
				override fun areItemsTheSame(
					oldItem: FileMatch,
					newItem: FileMatch,
				) = oldItem.relativePath == newItem.relativePath

				override fun areContentsTheSame(
					oldItem: FileMatch,
					newItem: FileMatch,
				) = oldItem == newItem
			}
	}
}

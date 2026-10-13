package org.appdevforall.codeonthego.models

import android.content.Context
import com.itsaky.androidide.resources.R
import org.appdevforall.codeonthego.utils.formatDate
import java.io.File
import java.util.UUID

class ProjectFile(
	path: String,
	val createdAt: String?,
	val lastModified: String?,
) {
	var path: String = path
		private set

	var name: String = lastSegment(path)
		private set

	fun isCaseOnlyRenameTo(newPath: String): Boolean {
		val source = File(path)
		val target = File(newPath)
		return source.parentFile?.absolutePath == target.parentFile?.absolutePath &&
			source.name != target.name &&
			source.name.equals(target.name, ignoreCase = true)
	}

	fun rename(newPath: String): Boolean {
		val source = File(path)
		val target = File(newPath)
		if (isCaseOnlyRenameTo(newPath) ||
			(
				source.absolutePath != target.absolutePath &&
					runCatching { source.canonicalPath == target.canonicalPath }.getOrDefault(false)
			)
		) {
			val temporary = File(source.parentFile, ".rename-${UUID.randomUUID()}")
			if (!source.renameTo(temporary)) return false
			if (!temporary.renameTo(target)) {
				if (!temporary.renameTo(source)) {
					throw IllegalStateException("Could not restore project folder after failed case-only rename")
				}
				return false
			}
		} else if (!source.renameTo(target)) {
			return false
		}
		path = newPath
		name = lastSegment(newPath)
		return true
	}

	fun renderDateText(context: Context): String {
		val showModified = createdAt != lastModified
		val renderDate = if (showModified) lastModified else createdAt
		val label =
			if (showModified) {
				context.getString(R.string.date_modified_label)
			} else {
				context.getString(R.string.date_created_label)
			}
		return context.getString(R.string.date, label, formatDate(renderDate ?: ""))
	}

	private companion object {
		fun lastSegment(path: String): String = path.substring(path.lastIndexOf("/") + 1)
	}
}

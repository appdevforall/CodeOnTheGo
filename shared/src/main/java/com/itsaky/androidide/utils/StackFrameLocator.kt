package com.itsaky.androidide.utils

import java.io.File

data class StackFrame(
	val className: String,
	val fileName: String,
	val line: Int,
)

object StackFrameLocator {
	private val framePattern = Regex("""\bat\s+([\w.$]+)\.[\w$<>]+\(([\w-]+\.(?:java|kt)):(\d+)\)""")

	fun parse(logLine: String): StackFrame? {
		val match = framePattern.find(logLine) ?: return null
		val (className, fileName, line) = match.destructured
		return StackFrame(className, fileName, line.toInt())
	}

	fun sourceLocationRange(logLine: String): IntRange? {
		val groups = framePattern.find(logLine)?.groups ?: return null
		return groups[2]!!.range.first..groups[3]!!.range.last
	}

	fun locate(
		frame: StackFrame,
		sourceDirs: Iterable<File>,
	): File? {
		val packagePath = frame.className.substringBeforeLast('.', "").replace('.', File.separatorChar)
		return sourceDirs
			.asSequence()
			.map { dir -> File(if (packagePath.isEmpty()) dir else File(dir, packagePath), frame.fileName) }
			.firstOrNull { it.isFile }
	}
}

package com.itsaky.androidide.utils
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes


fun getAttrs(location: String): BasicFileAttributes? {
	return runCatching {
		Files.readAttributes(Paths.get(location), BasicFileAttributes::class.java)
	}.getOrNull()
}

fun getLastModifiedTime(location: String): Long {
	val projectAttrs = getAttrs(location) ?: return System.currentTimeMillis()

	return projectAttrs.lastModifiedTime().toMillis()
}

fun getCreatedTime(location: String): Long {
	val projectAttrs = getAttrs(location) ?: return System.currentTimeMillis()

	return projectAttrs.creationTime().toMillis()
}
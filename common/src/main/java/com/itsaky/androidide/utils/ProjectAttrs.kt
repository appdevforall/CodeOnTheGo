package com.itsaky.androidide.utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes


fun getAttrs(location: String): BasicFileAttributes? {
	return runCatching {
		Files.readAttributes(Paths.get(location), BasicFileAttributes::class.java)
	}.getOrNull()
}

suspend fun getLastModifiedTime(location: String): Long = withContext(Dispatchers.IO) {
	val projectAttrs = getAttrs(location) ?: return@withContext System.currentTimeMillis()

	return@withContext projectAttrs.lastModifiedTime().toMillis()
}

suspend fun getCreatedTime(location: String): Long = withContext(Dispatchers.IO) {
	val projectAttrs = getAttrs(location) ?: return@withContext System.currentTimeMillis()

	return@withContext projectAttrs.creationTime().toMillis()
}
package com.itsaky.androidide.repositories

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadFilesRepositoryImpl(
	private val downloadDir: File,
) : DownloadFilesRepository {
	override suspend fun list(extension: String): List<DownloadedFile> =
		withContext(Dispatchers.IO) {
			downloadDir
				.listFiles { file -> file.isFile && file.extension.equals(extension, ignoreCase = true) }
				.orEmpty()
				.map { file -> DownloadedFile(file, file.length(), file.lastModified()) }
				// Newest first: the file the user just downloaded is the one they are looking for.
				.sortedByDescending { it.lastModified }
		}
}

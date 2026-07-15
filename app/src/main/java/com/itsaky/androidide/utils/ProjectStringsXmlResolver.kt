package com.itsaky.androidide.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves the project's default strings.xml file while ensuring access stays
 * within the provided project root.
 */
object ProjectStringsXmlResolver {

    private const val STRINGS_XML_RELATIVE_PATH = "app/src/main/res/values/strings.xml"

    suspend fun find(projectRootPath: String): File? = withContext(Dispatchers.IO) {
        findNow(projectRootPath)
    }

    fun findNow(projectRootPath: String): File? {
        val projectRoot = projectRootPath.toCanonicalDirectory() ?: return null
        val stringsFile = File(projectRoot, STRINGS_XML_RELATIVE_PATH).canonicalFile
        return stringsFile.takeIf {
            it.exists() && it.isFile && it.isWithin(projectRoot)
        }
    }

    private fun String.toCanonicalDirectory(): File? {
        val dir = File(this).canonicalFile
        return dir.takeIf { it.exists() && it.isDirectory }
    }

    private fun File.isWithin(root: File): Boolean {
        val rootPath = root.toPath()
        val filePath = toPath()
        return filePath.startsWith(rootPath)
    }
}

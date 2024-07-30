/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins.util

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FolderCopyUtils {

    companion object {
        @Throws(IOException::class)
        fun copyFolderWithInnerFolders(source: Path, target: Path) {
            if (!Files.exists(target)) {
                Files.createDirectories(target)
            }

            Files.newDirectoryStream(source).use { directoryStream ->
                for (path in directoryStream) {
                    val targetPath: Path = target.resolve(source.relativize(path))
                    if (Files.isDirectory(path)) {
                        copyFolderWithInnerFolders(path, targetPath)
                    } else {
                        Files.copy(
                            path,
                            targetPath,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }
            }
        }
    }

}
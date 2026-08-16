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

package com.itsaky.androidide.provider

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * AndroidIDE file provider.
 *
 * @author Akash Yadav
 */
class IDEFileProvider : FileProvider() {
	companion object {
		private const val AUTHORITY_SUFFIX = ".providers.fileprovider"

		/**
		 * Mint a `content://` [Uri] for [file] via this provider, so it can be shared with
		 * another component in this app without relying on a Uri permission grant to have
		 * carried over from wherever [file]'s bytes originally came from.
		 */
		@JvmStatic
		fun getUriForFile(
			context: Context,
			file: File,
		): Uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
	}
}

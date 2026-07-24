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

package com.itsaky.androidide.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile

object ImageUtils {
	enum class ImageType(
		val value: String,
	) {
		TYPE_JPG("jpg"),
		TYPE_PNG("png"),
		TYPE_GIF("gif"),
		TYPE_TIFF("tiff"),
		TYPE_BMP("bmp"),
		TYPE_WEBP("webp"),
		TYPE_ICO("ico"),
		TYPE_UNKNOWN(""),
	}

	/**
	 * Checks whether [file] can be decoded as a bitmap image.
	 */
	@JvmStatic
	fun isImage(file: File): Boolean {
		val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.absolutePath, options)
		return options.outWidth > 0 && options.outHeight > 0
	}

	/**
	 * Decodes [file] as a [Bitmap], or `null` if it isn't a valid/supported image.
	 */
	@JvmStatic
	fun getBitmap(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)

	/**
	 * Determines [file]'s image type by sniffing its magic-number header bytes, falling back to
	 * [ImageType.TYPE_UNKNOWN] if the file is missing, unreadable, or doesn't match a known format.
	 */
	@JvmStatic
	fun getImageType(file: File): ImageType {
		if (!file.isFile) return ImageType.TYPE_UNKNOWN

		val header = ByteArray(12)
		val read =
			try {
				RandomAccessFile(file, "r").use { it.read(header) }
			} catch (e: Exception) {
				return ImageType.TYPE_UNKNOWN
			}
		if (read < 4) return ImageType.TYPE_UNKNOWN

		fun byteAt(i: Int): Int = header[i].toInt() and 0xFF

		return when {
			byteAt(0) == 0xFF && byteAt(1) == 0xD8 && byteAt(2) == 0xFF -> ImageType.TYPE_JPG

			byteAt(0) == 0x89 && byteAt(1) == 0x50 && byteAt(2) == 0x4E && byteAt(3) == 0x47 -> ImageType.TYPE_PNG

			byteAt(0) == 0x47 && byteAt(1) == 0x49 && byteAt(2) == 0x46 -> ImageType.TYPE_GIF

			(byteAt(0) == 0x49 && byteAt(1) == 0x49) || (byteAt(0) == 0x4D && byteAt(1) == 0x4D) -> ImageType.TYPE_TIFF

			byteAt(0) == 0x42 && byteAt(1) == 0x4D -> ImageType.TYPE_BMP

			byteAt(0) == 0x00 && byteAt(1) == 0x00 && byteAt(2) == 0x01 && byteAt(3) == 0x00 -> ImageType.TYPE_ICO

			read >= 12 &&
				header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
				header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
				header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
				header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte() -> ImageType.TYPE_WEBP

			else -> ImageType.TYPE_UNKNOWN
		}
	}
}

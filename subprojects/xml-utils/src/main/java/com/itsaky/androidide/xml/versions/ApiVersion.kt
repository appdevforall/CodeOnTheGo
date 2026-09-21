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

package com.itsaky.androidide.xml.versions

/**
 * An Android API version, which since SDK 36.1 has a minor component.
 *
 * The packed value is AOSP's own encoding -- `major * 100_000 + minor`, the scheme behind
 * `Build.VERSION.SDK_INT_FULL` and `VERSION_CODES_FULL` -- so a version read from
 * `api-versions.xml` compares directly against what the running device reports. [UNKNOWN] is
 * `-1`, below every real version.
 */
@JvmInline
value class ApiVersion private constructor(
	val value: Long,
) : Comparable<ApiVersion> {
	/** The major version, e.g. `36` for API 36.1, or `-1` when this is [UNKNOWN]. */
	val major: Int
		get() = if (isKnown) (value / MINOR_SCALE).toInt() else -1

	/**
	 * The minor version, e.g. `1` for API 36.1. Zero for a version without a minor component, `-1`
	 * when this is [UNKNOWN].
	 */
	val minor: Int
		get() = if (isKnown) (value % MINOR_SCALE).toInt() else -1

	/** Whether this is a real version rather than [UNKNOWN]. */
	val isKnown: Boolean
		get() = value >= 0

	override fun compareTo(other: ApiVersion): Int = value.compareTo(other.value)

	override fun toString(): String =
		when {
			!isKnown -> "unknown"
			minor == 0 -> major.toString()
			else -> "$major.$minor"
		}

	companion object {
		/** The version of an element whose API version is absent or unreadable. */
		val UNKNOWN = ApiVersion(-1L)

		private const val MINOR_SCALE = 100_000L

		/**
		 * The packed version for [major] and [minor].
		 *
		 * @throws IllegalArgumentException if either component is out of range, which would pack onto
		 *   a different version -- `of(36, 100_000)` would otherwise read back as API 37.
		 */
		fun of(
			major: Int,
			minor: Int = 0,
		): ApiVersion {
			require(major >= 0) { "Negative major version: $major" }
			require(minor.toLong() in 0L until MINOR_SCALE) { "Minor version out of range: $minor" }
			return ApiVersion(major * MINOR_SCALE + minor)
		}

		/**
		 * Parses a `major` or `major.minor` version, as `api-versions.xml` and the SDK platform
		 * directory names spell it, or `null` if [value] is not one.
		 */
		fun parse(value: String?): ApiVersion? {
			if (value.isNullOrBlank()) {
				return null
			}

			val dot = value.indexOf('.')
			val major = (if (dot == -1) value else value.substring(0, dot)).toIntOrNull() ?: return null
			if (major < 0) {
				return null
			}

			if (dot == -1) {
				return of(major)
			}

			val minor = value.substring(dot + 1).toIntOrNull() ?: return null
			if (minor < 0 || minor >= MINOR_SCALE) {
				return null
			}

			return of(major, minor)
		}
	}
}

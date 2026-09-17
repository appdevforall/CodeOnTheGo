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

package com.itsaky.androidide.xml.internal.versions

import com.itsaky.androidide.xml.versions.ApiVersion
import com.itsaky.androidide.xml.versions.Info

/** @author Akash Yadav */
open class DefaultInfo(
	override val name: String,
	override val since: ApiVersion,
	override val removed: ApiVersion,
	override val deprecated: ApiVersion,
) : Info {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is DefaultInfo) return false

		if (name != other.name) return false
		if (since != other.since) return false
		if (removed != other.removed) return false
		if (deprecated != other.deprecated) return false

		return true
	}

	override fun hashCode(): Int {
		var result = name.hashCode()
		result = 31 * result + since.hashCode()
		result = 31 * result + removed.hashCode()
		result = 31 * result + deprecated.hashCode()
		return result
	}

	override fun toString(): String = "DefaultInfo(name='$name', since=$since, removed=$removed, deprecated=$deprecated)"
}

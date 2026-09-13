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

package com.itsaky.androidide.analytics

import android.os.Bundle
import com.itsaky.androidide.models.DeepLinkRequest

/**
 * How specific an incoming deep link was -- how far down
 * `project/{name}/file/{f}/line/{n}/column/{n}` it actually reached.
 */
enum class DeepLinkDepth {
	/** Not yet parsed, or not a link this app understands. */
	UNKNOWN,
	PROJECT,
	FILE,
	LINE,
	COLUMN,
}

/** What an incoming deep link ended up doing. */
enum class DeepLinkOutcome {
	/** Parsed and accepted, before anything was resolved. Pairs with a later terminal outcome. */
	RECEIVED,

	/** Dropped because the IDE has not finished onboarding -- see [DeepLinkOutcome] call site. */
	SETUP_INCOMPLETE,

	/** The URI did not parse as a link this app understands. */
	INVALID_LINK,

	/** No project of that name exists. */
	PROJECT_NOT_FOUND,

	/**
	 * Whether the project exists could not be determined (a filesystem failure unrelated to its
	 * existence). Kept apart from [PROJECT_NOT_FOUND] for the same reason the code does: a spike
	 * here means storage trouble, not people mistyping project names.
	 */
	PROJECT_UNVERIFIABLE,
}

/**
 * A deep link arrived (ADFA-5067). Emitted once with [DeepLinkOutcome.RECEIVED] when a link is
 * accepted, and again with whatever terminal outcome it reached, so the drop-off between the two is
 * visible -- every bug this feature shipped with looked identical from the outside (a link that
 * silently did nothing), and nothing on the device recorded that it had happened at all.
 */
class DeepLinkMetric(
	private val depth: DeepLinkDepth,
	private val outcome: DeepLinkOutcome,
	private val projectName: String? = null,
) : Metric {
	override val eventName: String = EVENT_NAME

	override fun asBundle(): Bundle =
		Bundle().apply {
			putString("depth", depth.name.lowercase())
			putString("outcome", outcome.name.lowercase())
			// Hashed, never the raw name -- a project name is the user's content, and a link can
			// carry a file path too. Matches trackProjectOpened's project_hash, which also makes the
			// two joinable without either of them carrying the name off the device.
			projectName?.let { putLong("project_hash", it.hashCode().toLong()) }
			putLong("timestamp", System.currentTimeMillis())
		}

	companion object {
		const val EVENT_NAME = "deep_link"
	}
}

/** How far down the optional segments this request actually reached. */
fun DeepLinkRequest.depth(): DeepLinkDepth {
	val file = fileRequest ?: return DeepLinkDepth.PROJECT
	return when {
		file.columnRaw != null -> DeepLinkDepth.COLUMN
		file.lineRaw != null -> DeepLinkDepth.LINE
		else -> DeepLinkDepth.FILE
	}
}

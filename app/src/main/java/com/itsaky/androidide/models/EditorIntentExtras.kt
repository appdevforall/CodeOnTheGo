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

package com.itsaky.androidide.models

/**
 * The Intent extras MainActivity and the editor activities pass between themselves.
 *
 * Spelled out here rather than repeated as string literals at each of their ~13 call sites across
 * three files. A typo in one of those reads back as a missing extra -- silently, at runtime -- and
 * [EXTRA_PREVIOUS_PROJECT_PATH] in particular is what the same-project-vs-genuine-switch decision
 * turns on, so getting it wrong makes every switch look like a no-op. The values keep their original
 * unqualified spelling: they are read from Intents that a previous install may have persisted, and
 * renaming them now would silently drop those.
 */
object EditorIntentExtras {
	/** Absolute path of the project the editor should open. */
	const val EXTRA_PROJECT_PATH = "PROJECT_PATH"

	/**
	 * Absolute path of the project that was open *before* this Intent was built.
	 *
	 * Sent because `recordProjectOpenedBookkeeping` overwrites the live `IProjectManager` global to
	 * the new path before the Intent is even delivered, so by the time the editor reads it there is
	 * no longer any way to tell what it was.
	 */
	const val EXTRA_PREVIOUS_PROJECT_PATH = "PREVIOUS_PROJECT_PATH"

	/** Set when the project was created from a template that reported issues. */
	const val EXTRA_HAS_TEMPLATE_ISSUES = "HAS_TEMPLATE_ISSUES"
}

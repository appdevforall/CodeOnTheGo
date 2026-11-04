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

package com.itsaky.androidide.actions.file

import com.itsaky.androidide.models.SaveResult

/**
 * What a finished save-all asks the editor to do next. Both members are decided from the save's
 * flags alone, which is why they are resolved here rather than inside the activity-bound
 * `postExec` that performs them.
 */
internal data class SaveFollowUp(
	/** Whether the save can have changed what `generateSources()` produces. */
	val regenerateSources: Boolean,
	/** Whether the save can have changed the build configuration, so the project needs a sync. */
	val markSyncNeeded: Boolean,
)

/**
 * Routes a completed [saveResult] to the follow-up work it warrants.
 *
 * Only a resource or manifest save can change what generateSources produces (R.jar, ViewBinding
 * accessors, the Manifest class - see [SaveResult.resourceXmlSaved]), so only those warrant the
 * Gradle run. Reading [SaveResult.xmlSaved] here instead is the regression to watch for:
 * previously ANY XML save triggered the run, and skipping it on other non-resource XML is a
 * save-latency win for every user. Deliberately un-gated, experiments flag off included.
 */
internal fun saveFollowUpFor(saveResult: SaveResult): SaveFollowUp =
	SaveFollowUp(
		regenerateSources = saveResult.resourceXmlSaved,
		markSyncNeeded = saveResult.gradleSaved,
	)

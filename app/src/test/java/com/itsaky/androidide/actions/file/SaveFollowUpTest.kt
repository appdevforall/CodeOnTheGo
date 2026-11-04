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

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.models.SaveResult
import org.junit.Test

/**
 * What a save asks the editor to do afterwards - the decision `SaveFileAction.postExec` acts on.
 *
 * Two defects sit on this routing, in opposite directions. Reading `xmlSaved` instead of
 * `resourceXmlSaved` brings back "every XML save triggers a Gradle run", a save-latency tax on
 * every user. Dropping the resource arm stops `R.jar` and the ViewBinding accessors being
 * regenerated, so a freshly added string or id will not resolve until the next sync.
 */
class SaveFollowUpTest {
	private fun saveResult(
		gradle: Boolean = false,
		xml: Boolean = false,
		resourceXml: Boolean = false,
	): SaveResult =
		SaveResult().apply {
			gradleSaved = gradle
			xmlSaved = xml
			resourceXmlSaved = resourceXml
		}

	@Test
	fun `a resource xml save asks for the generateSources run`() {
		// A resource save is also an xml save, which is how the two flags arrive together.
		val followUp = saveFollowUpFor(saveResult(xml = true, resourceXml = true))

		assertThat(followUp.regenerateSources).isTrue()
		assertThat(followUp.markSyncNeeded).isFalse()
	}

	@Test
	fun `a non-resource xml save asks for nothing - no Gradle run per xml save`() {
		val followUp = saveFollowUpFor(saveResult(xml = true))

		assertThat(followUp.regenerateSources).isFalse()
		assertThat(followUp.markSyncNeeded).isFalse()
	}

	@Test
	fun `a gradle save asks for a sync and not for generateSources`() {
		val followUp = saveFollowUpFor(saveResult(gradle = true))

		assertThat(followUp.markSyncNeeded).isTrue()
		assertThat(followUp.regenerateSources).isFalse()
	}

	@Test
	fun `a source-only save asks for nothing`() {
		val followUp = saveFollowUpFor(saveResult())

		assertThat(followUp.regenerateSources).isFalse()
		assertThat(followUp.markSyncNeeded).isFalse()
	}

	@Test
	fun `a save carrying both a manifest and a build script asks for both`() {
		val followUp = saveFollowUpFor(saveResult(gradle = true, xml = true, resourceXml = true))

		assertThat(followUp.regenerateSources).isTrue()
		assertThat(followUp.markSyncNeeded).isTrue()
	}
}

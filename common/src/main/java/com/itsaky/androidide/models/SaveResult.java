/************************************************************************************
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 **************************************************************************************/
package com.itsaky.androidide.models;

/** Result obtained when files are saved */
public final class SaveResult {

	/** Were any Gradle files saved? */
	public boolean gradleSaved = false;

	/** Were any XML files saved? */
	public boolean xmlSaved = false;

	/**
	 * Were any Android resource XML files (files under a module's {@code res/} directory) saved?
	 *
	 * <p>
	 * Narrower than {@link #xmlSaved} on purpose: only a resource save can change {@code R}, and the Gradle {@code generateSources()} run that follows a save is load-bearing exactly there. Java resolves {@code R.string.*} from the regenerated {@code R.jar} on the compile classpath (the run posts {@code ProjectInitializedEvent}, which makes {@code JavaLanguageServer} drop its stale jar-FS cache), and with view binding on, only {@code dataBindingGenBaseClasses} writes the accessor for an id just added to a layout. Manifest edits and other non-resource XML cannot change {@code R}, so they skip that run.
	 */
	public boolean resourceXmlSaved = false;

	public SaveResult() {}

	public SaveResult(boolean gradleSaved, boolean xmlSaved) {
		this.gradleSaved = gradleSaved;
		this.xmlSaved = xmlSaved;
	}
}

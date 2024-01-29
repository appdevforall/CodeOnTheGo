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

package com.itsaky.androidide.preferences.internal

/** Custom Gradle installation directory path. */

const val GITHUB_USERNAME = "idepref_githib_username"
const val GITHUB_EMAIL = "ideprefs_github_email"
const val GITHUB_PAT = "idepref_github_pat"


/** GitHub login username. */
var githubUsername: String
    get() = prefManager.getString(GITHUB_USERNAME, "")
    set(value) {
        prefManager.putString(GITHUB_USERNAME, value)
    }

/** GitHub login email. */
var githubEmail: String
    get() = prefManager.getString(GITHUB_EMAIL, "")
    set(value) {
        prefManager.putString(GITHUB_EMAIL, value)
    }

/** GitHub personal access token. */
var githubPAT: String
    get() = prefManager.getString(GITHUB_PAT, "")
    set(value) {
        prefManager.putString(GITHUB_PAT, value)
    }



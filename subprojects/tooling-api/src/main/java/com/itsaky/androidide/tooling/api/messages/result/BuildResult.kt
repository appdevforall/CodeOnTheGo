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

package com.itsaky.androidide.tooling.api.messages.result

import com.itsaky.androidide.tooling.api.messages.BuildId

/**
 * Result obtained on a build success or failure.
 *
 * @author Akash Yadav
 */
data class BuildResult(
	val buildId: BuildId,
	val tasks: List<String>,
	val durationMs: Long,
	/**
	 * Why the build failed, or `null` if it succeeded.
	 *
	 * The server is the only party that can answer this: Gradle raises a
	 * `BuildCancelledException` for a build the user stopped, and the same throwable that decides
	 * the [TaskExecutionResult] decides this. Without it a client had to reconstruct "was that a
	 * cancel?" from the order its own callbacks happened to arrive in, and got it wrong
	 * (ADFA-5542).
	 */
	val failure: TaskExecutionResult.Failure? = null,
)

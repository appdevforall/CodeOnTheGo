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

import java.io.File
import java.io.Serializable

/**
 * Result received after an initialize project request.
 *
 * @author Akash Yadav
 */
sealed class InitializeResult : Serializable {
	protected val gsonType: String = javaClass.name
	protected val serialVersionUID = 1L

	/**
	 * The project initialization was successful.
	 */
	data class Success(
		val cacheFile: File,
	) : InitializeResult()

	/**
	 * The project initialization failed.
	 */
	data class Failure(
		val failure: TaskExecutionResult.Failure? = null,
	) : InitializeResult()
}

/**
 * Whether the project initialization was successful.
 */
val InitializeResult.isSuccessful: Boolean
	get() = this is InitializeResult.Success

/**
 * Get the failure of the project initialization, if the initialization failed.
 */
val InitializeResult.failure: TaskExecutionResult.Failure?
	get() = (this as? InitializeResult.Failure)?.failure

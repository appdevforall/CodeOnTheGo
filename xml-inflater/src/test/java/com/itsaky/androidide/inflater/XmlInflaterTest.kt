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

package com.itsaky.androidide.inflater

import androidx.appcompat.app.AppCompatActivity
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.inflater.utils.endParse
import com.itsaky.androidide.inflater.utils.startParse
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.util.findAppModule
import com.itsaky.androidide.testing.tooling.ToolingApiTestLauncher
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.robolectric.Robolectric

@Ignore("Test utility provider")
object XmlInflaterTest {
	private enum class State { UNINITIALIZED, READY, FAILED }

	@Volatile private var state: State = State.UNINITIALIZED
	@Volatile private var initError: Throwable? = null

	internal val activity by lazy { Robolectric.buildActivity(AppCompatActivity::class.java).get() }

	fun initIfNeeded() {
		// Fast path: once we've reached a terminal state, no synchronization needed.
		when (state) {
			State.READY -> return
			State.FAILED -> throw IllegalStateException(
				"XmlInflaterTest init previously failed; refusing to retry.",
				initError,
			)
			State.UNINITIALIZED -> {} // fall through to slow path
		}

		// Slow path: exactly one thread runs setup; losers block on the monitor
		// until the winner exits, then observe the terminal state and either
		// proceed (READY) or throw (FAILED).
		synchronized(this) {
			when (state) {
				State.READY -> return
				State.FAILED -> throw IllegalStateException(
					"XmlInflaterTest init previously failed; refusing to retry.",
					initError,
				)
				State.UNINITIALIZED -> {
					try {
						ToolingApiTestLauncher.launchServer {
							assertThat(result is InitializeResult.Success).isTrue()
							runBlocking { IProjectManager.getInstance().setup(gradleBuild.get()) }
						}
						state = State.READY
					} catch (error: Throwable) {
						initError = error
						state = State.FAILED
						throw error
					}
				}
			}
		}
	}
}

fun inflaterTest(block: (AndroidModule) -> Unit) {
	XmlInflaterTest.initIfNeeded()
	val app = findAppModule()!!
	startParse(app)
	block(app)
	endParse()
}

fun requiresActivity(block: AppCompatActivity.() -> Unit) {
	XmlInflaterTest.activity.block()
}

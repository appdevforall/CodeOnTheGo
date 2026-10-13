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

package org.appdevforall.codeonthego.handlers

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.appdevforall.codeonthego.eventbus.events.Event
import org.appdevforall.codeonthego.eventbus.events.EventReceiver
import org.appdevforall.codeonthego.eventbus.events.editor.OnCreateEvent
import org.appdevforall.codeonthego.eventbus.events.editor.OnDestroyEvent
import org.appdevforall.codeonthego.eventbus.events.editor.OnPauseEvent
import org.appdevforall.codeonthego.eventbus.events.editor.OnResumeEvent
import org.appdevforall.codeonthego.eventbus.events.editor.OnStartEvent
import org.appdevforall.codeonthego.eventbus.events.editor.OnStopEvent
import org.appdevforall.codeonthego.projects.ProjectManagerImpl
import org.appdevforall.codeonthego.projects.util.BootClasspathProvider
import org.appdevforall.codeonthego.utils.EditorActivityActions
import org.appdevforall.codeonthego.utils.EditorSidebarActions
import org.appdevforall.codeonthego.utils.Environment
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.CompletableFuture

/**
 * Observes lifecycle events if [org.appdevforall.codeonthego.EditorActivityKt].
 *
 * @author Akash Yadav
 */
class EditorActivityLifecyclerObserver : DefaultLifecycleObserver {
	private val fileActionsHandler = FileTreeActionHandler()

	override fun onCreate(owner: LifecycleOwner) {
		EditorActivityActions.register(owner as Context)
		EditorSidebarActions.registerActions(owner as Context)
		dispatchEvent(OnCreateEvent())
	}

	override fun onStart(owner: LifecycleOwner) {
		CompletableFuture.runAsync(this::initBootclasspathProvider)
		register(fileActionsHandler, ProjectManagerImpl.getInstance())

		dispatchEvent(OnStartEvent())
	}

	override fun onResume(owner: LifecycleOwner) {
		EditorActivityActions.register(owner as Context)
		dispatchEvent(OnResumeEvent())
	}

	override fun onPause(owner: LifecycleOwner) {
		EditorActivityActions.clearActions()
		dispatchEvent(OnPauseEvent())
	}

	override fun onStop(owner: LifecycleOwner) {
		unregister(fileActionsHandler, ProjectManagerImpl.getInstance())
		dispatchEvent(OnStopEvent())
	}

	override fun onDestroy(owner: LifecycleOwner) {
		dispatchEvent(OnDestroyEvent())
	}

	private fun register(vararg receivers: EventReceiver) {
		receivers.forEach { it.register() }
	}

	private fun unregister(vararg receivers: EventReceiver) {
		receivers.forEach { it.unregister() }
	}

	private fun dispatchEvent(event: Event) {
		EventBus.getDefault().post(event)
	}

	private fun initBootclasspathProvider() {
		BootClasspathProvider.update(listOf(Environment.ANDROID_JAR.absolutePath))
	}
}

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
package com.itsaky.androidide.lsp.api

import com.itsaky.androidide.eventbus.events.project.ProjectInitializedEvent
import com.itsaky.androidide.lsp.debug.DebugClientConnectionResult
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.utils.ILogger
import kotlinx.coroutines.CancellationException
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Objects
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Thread-safe implementation of [ILanguageServerRegistry].
 * 
 * @author Akash Yadav
 */
class DefaultLanguageServerRegistry : ILanguageServerRegistry() {
	private val mRegister = HashMap<String, ILanguageServer>()
	private val lock: ReadWriteLock = ReentrantReadWriteLock()

	override fun connectClient(client: ILanguageClient) {
		Objects.requireNonNull(client)
		lock.readLock().lock()
		try {
			for (server in mRegister.values) {
				server.connectClient(client)
			}
		} finally {
			lock.readLock().unlock()
		}
	}

	@Throws(Throwable::class)
	override suspend fun connectDebugClient(client: IDebugClient): Map<String, DebugClientConnectionResult> {
		Objects.requireNonNull(client)
		val servers = lock.readLock().withLock {
			mRegister.values.toList()
		}

		return buildMap {
			for (server in servers) {
				try {
					this[server.serverId] = server.connectDebugClient(client)
				} catch (e: Throwable) {
					if (e is CancellationException) {
						throw e
					}

					sLogger.error(
						"Unable to connect LSP server '{}' to debug client",
						server.serverId,
						e
					)

					this[server.serverId] = DebugClientConnectionResult.Failure(cause = e)
				}
			}
		}
	}

	override fun destroy() {
		EventBus.getDefault().unregister(this)
		lock.readLock().lock()
		try {
			for (server in mRegister.values) {
				server.shutdown()
			}
		} finally {
			lock.readLock().unlock()
		}

		lock.writeLock().lock()
		try {
			mRegister.clear()
		} finally {
			lock.writeLock().unlock()
		}
	}

	override fun getServer(serverId: String): ILanguageServer? {
		lock.readLock().lock()
		try {
			return mRegister[serverId]
		} finally {
			lock.readLock().unlock()
		}
	}

	@Subscribe(threadMode = ThreadMode.BACKGROUND)
	@Suppress("unused")
	fun onProjectInitialized(event: ProjectInitializedEvent) {
		val project = event.get(Workspace::class.java) ?: return

		ILogger.ROOT.debug("Dispatching ProjectInitializedEvent to language servers...")
		val servers = lock.readLock().withLock { mRegister.values.toList() }
		for (server in servers) {
			server.setupWithProject(project)
		}
	}

	override fun register(server: ILanguageServer) {
		if (!EventBus.getDefault().isRegistered(this)) {
			EventBus.getDefault().register(this)
		}

		lock.writeLock().lock()
		try {
			val old = mRegister.put(server.serverId, server)
			if (old != null) {
				mRegister[old.serverId] = old
			}
		} finally {
			lock.writeLock().unlock()
		}
	}

	override fun unregister(serverId: String) {
		lock.writeLock().lock()
		try {
			val registered = mRegister.remove(serverId)
			checkNotNull(registered) { "No server found for the given server ID" }
		} finally {
			lock.writeLock().unlock()
		}
	}

	companion object {
		private val sLogger: Logger =
			LoggerFactory.getLogger(DefaultLanguageServerRegistry::class.java)
	}
}

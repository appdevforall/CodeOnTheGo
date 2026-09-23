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

package com.itsaky.androidide.xml.internal.widgets

import com.google.auto.service.AutoService
import com.itsaky.androidide.xml.widgets.WidgetTable
import com.itsaky.androidide.xml.widgets.WidgetTableRegistry
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of the [WidgetTableRegistry].
 *
 * @author Akash Yadav
 */
@AutoService(WidgetTableRegistry::class)
class DefaultWidgetTableRegistry : WidgetTableRegistry {
	private val tables = ConcurrentHashMap<String, WidgetTable>()

	// Platform dirs with no readable widget list. See DefaultApiVersionsRegistry.
	private val unreadablePlatforms = ConcurrentHashMap.newKeySet<String>()

	companion object {
		private val log = LoggerFactory.getLogger(DefaultWidgetTableRegistry::class.java)
	}

	override var isLoggingEnabled: Boolean = true

	override fun forPlatformDir(platform: File): WidgetTable? {
		tables[platform.path]?.let { return it }
		if (platform.path in unreadablePlatforms) {
			return null
		}

		/*
		 * This table only feeds layout completion, and it is read on the path that opens a
		 * project. A platform file we cannot read must therefore cost the completion, not the
		 * project.
		 */
		val table =
			try {
				createTable(platform)
			} catch (e: Exception) {
				log.warn("Could not read widgets for platform dir: {}", platform, e)
				null
			}

		if (table == null) {
			unreadablePlatforms += platform.path
			return null
		}

		tables[platform.path] = table
		return table
	}

	private fun createTable(platformDir: File): WidgetTable? {
		val widgets = File(platformDir, "data/widgets.txt")
		if (!widgets.exists() || !widgets.isFile) {
			if (isLoggingEnabled) {
				log.warn("'widgets.txt' file does not exist in {}/data directory", platformDir.absolutePath)
			}
			return null
		}

		if (isLoggingEnabled) {
			log.info("Creating widget table for platform dir: {}", platformDir)
		}

		return widgets.inputStream().bufferedReader().useLines {
			val table = DefaultWidgetTable()
			it.forEach { line ->
				table.putWidget(line)
			}
			table
		}
	}

	override fun clear() {
		tables.clear()
		unreadablePlatforms.clear()
	}
}

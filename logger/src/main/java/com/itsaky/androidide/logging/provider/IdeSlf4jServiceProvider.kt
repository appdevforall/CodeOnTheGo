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

package com.itsaky.androidide.logging.provider

import com.google.auto.service.AutoService
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * Logback-free SLF4J 2.x provider backed by [IdeLogRouter]/[IdeLogger].
 *
 * @author Akash Yadav
 */
@AutoService(SLF4JServiceProvider::class)
class IdeSlf4jServiceProvider : SLF4JServiceProvider {
	private val loggerFactory = IdeLoggerFactory()
	private val markerFactory: IMarkerFactory = BasicMarkerFactory()
	private val mdcAdapter: MDCAdapter = NOPMDCAdapter()

	override fun getLoggerFactory() = loggerFactory

	override fun getMarkerFactory() = markerFactory

	override fun getMDCAdapter() = mdcAdapter

	override fun getRequestedApiVersion() = "2.0.99"

	override fun initialize() {
		// No-op: nothing needs eager setup.
	}
}

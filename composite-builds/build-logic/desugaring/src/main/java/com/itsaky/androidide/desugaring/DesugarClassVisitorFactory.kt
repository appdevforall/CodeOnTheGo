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
package com.itsaky.androidide.desugaring

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor
import org.slf4j.LoggerFactory

/**
 * [AsmClassVisitorFactory] implementation for desugaring.
 *
 * @author Akash Yadav
 */
abstract class DesugarClassVisitorFactory : AsmClassVisitorFactory<DesugarParams> {

	companion object {
		private val log =
			LoggerFactory.getLogger(DesugarClassVisitorFactory::class.java)
	}

	private val desugarParams: DesugarParams?
		get() = parameters.orNull ?: run {
			log.warn("Could not find desugaring parameters. Disabling desugaring.")
			null
		}

	override fun createClassVisitor(
		classContext: ClassContext,
		nextClassVisitor: ClassVisitor,
	): ClassVisitor {
		val params = desugarParams ?: return nextClassVisitor
		return DesugarClassVisitor(
			params = params,
			classContext = classContext,
			api = instrumentationContext.apiVersion.get(),
			classVisitor = nextClassVisitor,
		)
	}

	override fun isInstrumentable(classData: ClassData): Boolean {
		val params = desugarParams ?: return false

		val isEnabled = params.enabled.get().also { log.debug("Is desugaring enabled: $it") }
		if (!isEnabled) return false

		// Class-reference replacement must scan every class — any class may
		// contain a reference to the one being replaced, regardless of package.
		if (params.classReplacements.get().isNotEmpty()) return true

		val includedPackages = params.includedPackages.get()
		if (includedPackages.isNotEmpty()) {
			if (!includedPackages.any { classData.className.startsWith(it) }) {
				return false
			}
		}

		return true
	}
}
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

import com.android.build.api.instrumentation.ClassContext
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor

/**
 * [ClassVisitor] implementation for desugaring.
 *
 * @author Akash Yadav
 */
class DesugarClassVisitor(
	private val params: DesugarParams,
	private val classContext: ClassContext,
	api: Int,
	classVisitor: ClassVisitor,
) : ClassVisitor(api, classVisitor) {

	/**
	 * Class replacement map in ASM internal (slash) notation.
	 * Derived lazily from the dot-notation map stored in [params].
	 */
	private val slashClassReplacements: Map<String, String> by lazy {
		params.classReplacements.get()
			.entries.associate { (from, to) ->
				from.replace('.', '/') to to.replace('.', '/')
			}
	}

	override fun visitField(
		access: Int,
		name: String,
		descriptor: String,
		signature: String?,
		value: Any?,
	): FieldVisitor? = super.visitField(
		access,
		name,
		replaceInDescriptor(descriptor),
		replaceInSignature(signature),
		value,
	)

	override fun visitMethod(
		access: Int,
		name: String?,
		descriptor: String?,
		signature: String?,
		exceptions: Array<out String>?,
	): MethodVisitor {
		// Rewrite the method's own descriptor/signature at the class-structure level.
		val base = super.visitMethod(
			access,
			name,
			descriptor?.let { replaceInDescriptor(it) },
			replaceInSignature(signature),
			exceptions,
		)

		// Layer 1 — class-reference replacement inside the method body.
		// Skip instantiation entirely when there are no class replacements.
		val withClassRefs: MethodVisitor = when {
			slashClassReplacements.isNotEmpty() ->
				ClassRefReplacingMethodVisitor(api, base, slashClassReplacements)
			else -> base
		}

		// Layer 2 — fine-grained method-call replacement.
		// Runs first; any instruction it emits flows through withClassRefs.
		return DesugarMethodVisitor(params, classContext, api, withClassRefs)
	}

	private fun replaceInDescriptor(descriptor: String): String {
		if (slashClassReplacements.isEmpty()) return descriptor
		var result = descriptor
		for ((from, to) in slashClassReplacements) {
			result = result.replace("L$from;", "L$to;")
		}
		return result
	}

	private fun replaceInSignature(signature: String?): String? =
		signature?.let { replaceInDescriptor(it) }
}
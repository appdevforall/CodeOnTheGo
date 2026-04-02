package com.itsaky.androidide.desugaring

import com.android.build.api.instrumentation.InstrumentationParameters
import com.itsaky.androidide.desugaring.dsl.ReplaceMethodInsn
import com.itsaky.androidide.desugaring.dsl.ReplaceMethodInsnKey
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input

/**
 * Parameters for [DesugarClassVisitorFactory].
 *
 * @author Akash Yadav
 */
interface DesugarParams : InstrumentationParameters {

	/** Whether desugaring is enabled. */
	@get:Input
	val enabled: Property<Boolean>

	/** Fine-grained method-call replacement instructions. */
	@get:Input
	val replacements: MapProperty<ReplaceMethodInsnKey, ReplaceMethodInsn>

	/** Packages to scan for method-level replacements (empty = all packages). */
	@get:Input
	val includedPackages: SetProperty<String>

	/**
	 * Class-level replacement map: dot-notation source class → dot-notation
	 * target class. Any class may be instrumented when this is non-empty.
	 */
	@get:Input
	val classReplacements: MapProperty<String, String>

	companion object {

		fun DesugarParams.setFrom(extension: DesugarExtension) {
			replacements.convention(emptyMap())
			includedPackages.convention(emptySet())
			classReplacements.convention(emptyMap())

			enabled.set(extension.enabled)
			replacements.set(extension.replacements.instructions)
			includedPackages.set(extension.replacements.includePackages)
			classReplacements.set(extension.replacements.classReplacements)
		}
	}
}
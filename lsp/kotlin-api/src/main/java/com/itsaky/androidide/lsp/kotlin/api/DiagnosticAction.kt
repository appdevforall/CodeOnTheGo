package com.itsaky.androidide.lsp.kotlin.api

/**
 * The fix-it action (if any) a Kotlin diagnostic carries, attached to a
 * `DiagnosticItem.extra` as a [KotlinDiagnosticExtra]. Resident (used by
 * `BaseKotlinCodeAction`'s subclasses), unlike the [KotlinDiagnosticExtra.compilationEnv]
 * it travels with, which is only ever a real `CompilationEnvironment` once you're
 * inside the isolated `lsp:kotlin-compiler-impl` module.
 */
sealed interface DiagnosticAction {
	data object None : DiagnosticAction

	data class ResolveReference(
		val referenceName: String,
	) : DiagnosticAction

	data object NullSafetyFix : DiagnosticAction
}

data class KotlinDiagnosticExtra<out ActionT : DiagnosticAction>(
	val compilationEnv: IKotlinCompilationEnvironment,
	val action: ActionT,
)

@Suppress("UNCHECKED_CAST")
inline fun <reified T : DiagnosticAction> KotlinDiagnosticExtra<*>?.asAction(): KotlinDiagnosticExtra<T>? =
	if (this?.action is T) this as KotlinDiagnosticExtra<T> else null

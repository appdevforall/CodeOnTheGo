package com.itsaky.androidide.lsp.java.debug.utils

import org.jetbrains.kotlin.codegen.AsmUtil.INLINE_DECLARATION_SITE_THIS
import org.jetbrains.kotlin.codegen.AsmUtil.THIS_IN_DEFAULT_IMPLS
import org.jetbrains.kotlin.codegen.coroutines.CONTINUATION_VARIABLE_NAME
import org.jetbrains.kotlin.codegen.coroutines.SUSPEND_CALL_RESULT_NAME
import org.jetbrains.kotlin.codegen.coroutines.SUSPEND_FUNCTION_COMPLETION_PARAMETER_NAME
import org.jetbrains.kotlin.codegen.inline.INLINE_FUN_VAR_SUFFIX

/**
 * The inline marker prefix. The compiler writes this literal itself and exports no constant for it,
 * unlike the other names here.
 */
private const val INLINE_MARKER_PREFIX = "\$i\$"

private val SUSPEND_MACHINE_LOCALS =
	setOf(
		CONTINUATION_VARIABLE_NAME,
		SUSPEND_CALL_RESULT_NAME,
		SUSPEND_FUNCTION_COMPLETION_PARAMETER_NAME,
	)

/**
 * Whether [name] is a local the Kotlin compiler generated rather than one the user wrote.
 *
 * Covers the three shapes ADFA-4191 section 3 lists: the inline markers (`$i$f$` for an inlined
 * function body, `$i$a$` for an inlined lambda argument), the inline receivers, and the suspend
 * state machine's own locals. These carry no meaning for someone reading their own code, so the
 * variables list hides them.
 *
 * The receivers come in two shapes. Everything the compiler prefixes with `$this` is one -
 * `$this$map`, `$this_foo`, `$this` - and a member inline function's declaration-site receiver is
 * the bare `this_`, which shares no prefix with them. `this_` is matched exactly rather than as a
 * prefix, so a local the user named `this_count` is left alone.
 *
 * A marker says what to hide, not whose code is running: `$i$a$` scopes a lambda the user wrote,
 * inlined into the user's own class, exactly as `$i$f$` scopes a library body inlined there.
 *
 * Hiding every `$this$` costs the receiver of a `run`/`apply` block the user wrote, which is a real
 * variable under an unwritable name: the compiler emits it as `$this$<caller>_u24lambda_u24<n>`, and
 * no label a user could have typed is recoverable from that. The alternative was showing `this@map`
 * for a stdlib extension receiver, which does not compile anywhere in their source.
 *
 * The `$iv` an inlining appends is deliberately left on the names that survive. `item$iv$iv` and
 * `destination$iv$iv` are `mapTo`'s loop variable and accumulator, not the user's, and the suffix is
 * the only cue marking them so: shown as `item` and `destination` they sit beside a user's own
 * `item` with nothing to tell them apart. The cost is that a user's own inline-function local reads
 * `started$iv`. Separating the two needs the Kotlin stratum.
 */
fun isSyntheticKotlinLocal(name: String): Boolean =
	name.startsWith(INLINE_MARKER_PREFIX) ||
		name.startsWith(THIS_IN_DEFAULT_IMPLS) ||
		isDeclarationSiteReceiver(name) ||
		name in SUSPEND_MACHINE_LOCALS

/**
 * Whether [name] is the declaration-site receiver a member inline function leaves behind, `this_`,
 * possibly carrying one `$iv` per inlining.
 */
private fun isDeclarationSiteReceiver(name: String): Boolean =
	name == INLINE_DECLARATION_SITE_THIS ||
		name.startsWith(INLINE_DECLARATION_SITE_THIS + INLINE_FUN_VAR_SUFFIX)

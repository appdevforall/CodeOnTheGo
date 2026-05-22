package com.itsaky.androidide.compose.preview.runtime

import java.lang.reflect.Method

sealed class ComposeSignature {
    object NoArgs : ComposeSignature()

    class WithComposer(
        val composerIndex: Int,
        val totalParams: Int,
        val types: Array<Class<*>>
    ) : ComposeSignature()

    class Unsupported(val reason: String) : ComposeSignature()

    companion object {
        fun analyze(method: Method): ComposeSignature {
            val types = method.parameterTypes
            val paramCount = types.size

            if (paramCount == 0) return NoArgs

            val composerIndex = types.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }

            if (composerIndex == -1) {
                return Unsupported("No Composer parameter found in ${method.name}")
            }

            for (i in (composerIndex + 1) until paramCount) {
                if (types[i] != Int::class.javaPrimitiveType && types[i] != Integer::class.java) {
                    return Unsupported("Expected Int at index $i after Composer, but found ${types[i].simpleName}")
                }
            }

            return WithComposer(composerIndex, paramCount, types)
        }
    }
}

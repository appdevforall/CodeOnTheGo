package com.itsaky.androidide.compose.preview.runtime

import androidx.compose.runtime.Composer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier as ReflectModifier
import kotlin.math.ceil

class PreviewSetupException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ComposableInvoker {

    fun findComposableMethod(clazz: Class<*>, functionName: String): Method? {
        val methods = clazz.declaredMethods

        methods.find { it.name == functionName }?.let {
            it.isAccessible = true
            return it
        }

        val candidates = methods.filter { method ->
            !method.name.contains("\$default") &&
                (method.name.startsWith("$functionName\$") || method.name == "${functionName}\$lambda")
        }

        return candidates.minByOrNull { it.parameterCount }?.also { it.isAccessible = true }
    }

    fun invokeSafely(clazz: Class<*>, method: Method, composer: Composer) {
        val isStatic = ReflectModifier.isStatic(method.modifiers)

        val instance = if (isStatic) {
            null
        } else {
            try {
                clazz.getDeclaredConstructor().newInstance()
            } catch (e: Exception) {
                throw PreviewSetupException("Failed to create instance for ${clazz.simpleName}", e)
            }
        }

        if (!isStatic && instance == null) {
            throw PreviewSetupException("Failed to create instance for ${clazz.simpleName}")
        }

        when (val signature = ComposeSignature.analyze(method)) {
            is ComposeSignature.NoArgs -> executeInvocation { method.invoke(instance) }
            is ComposeSignature.WithComposer -> invokeWithComposer(method, instance, signature, composer)
            is ComposeSignature.Unsupported -> {
                throw PreviewSetupException("Unsupported signature: ${signature.reason}")
            }
        }
    }

    private fun invokeWithComposer(
        method: Method,
        instance: Any?,
        signature: ComposeSignature.WithComposer,
        composer: Composer
    ) {
        val args = arrayOfNulls<Any>(signature.totalParams)
        val realParamsCount = signature.composerIndex

        for (i in 0 until realParamsCount) {
            args[i] = getDefaultValue(signature.types[i])
        }

        args[signature.composerIndex] = composer

        val changedInts = if (realParamsCount == 0) 1 else ceil(realParamsCount / COMPOSE_PARAMS_PER_CHANGED_INT).toInt()
        val changedStartIndex = signature.composerIndex + 1
        val changedEndIndex = minOf(changedStartIndex + changedInts, signature.totalParams)

        args.fill(COMPOSE_CHANGED_EVALUATE_ALL, fromIndex = changedStartIndex, toIndex = changedEndIndex)
        args.fill(COMPOSE_DEFAULT_USE_ALL_DEFAULTS, fromIndex = changedEndIndex, toIndex = signature.totalParams)

        executeInvocation { method.invoke(instance, *args) }
    }

    private fun executeInvocation(action: () -> Unit) {
        try {
            action()
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        } catch (e: Exception) {
            throw PreviewSetupException("Reflection invocation failed", e)
        }
    }

    private fun getDefaultValue(type: Class<*>): Any? {
        if (!type.isPrimitive) return null
        return when (type) {
            Int::class.javaPrimitiveType -> 0
            Boolean::class.javaPrimitiveType -> false
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Long::class.javaPrimitiveType -> 0L
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
    }

    private const val COMPOSE_PARAMS_PER_CHANGED_INT = 10.0
    private const val COMPOSE_CHANGED_EVALUATE_ALL = 0
    private const val COMPOSE_DEFAULT_USE_ALL_DEFAULTS = -1
}

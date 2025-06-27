package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.java.debug.utils.JdiTypes.createBoxedPrimitive
import com.sun.jdi.BooleanType
import com.sun.jdi.ByteType
import com.sun.jdi.CharType
import com.sun.jdi.DoubleType
import com.sun.jdi.FloatType
import com.sun.jdi.IntegerType
import com.sun.jdi.LongType
import com.sun.jdi.Mirror
import com.sun.jdi.PrimitiveType
import com.sun.jdi.ReferenceType
import com.sun.jdi.ShortType
import com.sun.jdi.ThreadReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

object VariableValues {

    private val logger = LoggerFactory.getLogger(VariableValues::class.java)

    /**
     * Returns `true` if we support mutating a variable of the given type.
     */
    fun canMutate(type: Type): Boolean = when (type) {
        is PrimitiveType -> true
        is ReferenceType -> when (type.name()) {

            // when it's a reference type, we only support mutating boxed primitives or Strings
            JdiTypes.BOOLEAN,
            JdiTypes.BYTE,
            JdiTypes.CHARACTER,
            JdiTypes.SHORT,
            JdiTypes.INTEGER,
            JdiTypes.LONG,
            JdiTypes.FLOAT,
            JdiTypes.DOUBLE,
            JdiTypes.STRING -> true

            // other reference types are unsupported
            else -> false
        }

        else -> false
    }

    /**
     * Parse the given [value] according to the given [type].
     *
     * @param mirror The [Mirror] instance that is used to create [Value] instances.
     * @param type The [Type] of the variable.
     * @param value The value to parse.
     */
    suspend fun parseValue(
        thread: ThreadReference,
        mirror: Mirror,
        type: Type,
        value: String
    ): Value? = when (type) {
        is BooleanType -> value.jdiBool(mirror = mirror)
        is ByteType -> value.jdiByte(mirror = mirror)
        is CharType -> value.jdiChar(mirror = mirror)
        is ShortType -> value.jdiShort(mirror = mirror)
        is IntegerType -> value.jdiInt(mirror = mirror)
        is LongType -> value.jdiLong(mirror = mirror)
        is FloatType -> value.jdiFloat(mirror = mirror)
        is DoubleType -> value.jdiDouble(mirror = mirror)

        // creating new reference types requires a new mirror to be created in the remote VM
        // hence, communication is required over the socket connection, which should be performed
        // on the IO dispatcher
        is ReferenceType -> {
            val result = when (type.name()) {
                JdiTypes.BOOLEAN -> value.jdiBool(mirror)
                    ?.let { createBoxedPrimitive(thread, BoxedPrimitive.BOOLEAN, it) }

                JdiTypes.BYTE -> value.jdiByte(mirror)
                    ?.let { createBoxedPrimitive(thread, BoxedPrimitive.BYTE, it) }

                JdiTypes.CHARACTER -> value.jdiChar(mirror)
                    ?.let { createBoxedPrimitive(thread, BoxedPrimitive.CHARACTER, it) }

                JdiTypes.SHORT -> value.jdiShort(mirror)
                    ?.let { createBoxedPrimitive(thread, BoxedPrimitive.SHORT, it) }

                JdiTypes.INTEGER -> value.jdiInt(mirror)
                    ?.let { createBoxedPrimitive(thread, BoxedPrimitive.INTEGER, it) }

                JdiTypes.LONG -> value.jdiLong(mirror)
                    ?.let { createBoxedPrimitive(thread, BoxedPrimitive.LONG, it) }

                JdiTypes.FLOAT -> value.jdiFloat(mirror)
                    ?.let { createBoxedPrimitive(thread, BoxedPrimitive.FLOAT, it) }

                JdiTypes.DOUBLE -> value.jdiDouble(mirror)
                    ?.let { createBoxedPrimitive(thread, BoxedPrimitive.DOUBLE, it) }

                JdiTypes.STRING -> Result.success(value.let(mirror::mirrorOf))

                else -> null
            }

            if (result?.isFailure == true) {
                logger.error("Failed to parse value for boxed type", result.exceptionOrNull())
            }

            result?.getOrNull()
        }

        else -> null
    }
}

private fun String.jdiBool(mirror: Mirror): Value? = toBooleanStrictOrNull()?.let(mirror::mirrorOf)
private fun String.jdiByte(mirror: Mirror): Value? = toByteOrNull()?.let(mirror::mirrorOf)
private fun String.jdiShort(mirror: Mirror): Value? = toShortOrNull()?.let(mirror::mirrorOf)
private fun String.jdiChar(mirror: Mirror): Value? = singleOrNull()?.let(mirror::mirrorOf)
private fun String.jdiInt(mirror: Mirror): Value? = toIntOrNull()?.let(mirror::mirrorOf)
private fun String.jdiLong(mirror: Mirror): Value? = toLongOrNull()?.let(mirror::mirrorOf)
private fun String.jdiFloat(mirror: Mirror): Value? = toFloatOrNull()?.let(mirror::mirrorOf)
private fun String.jdiDouble(mirror: Mirror): Value? = toDoubleOrNull()?.let(mirror::mirrorOf)

package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.BooleanType
import com.sun.jdi.ByteType
import com.sun.jdi.CharType
import com.sun.jdi.DoubleType
import com.sun.jdi.FloatType
import com.sun.jdi.IntegerType
import com.sun.jdi.LongType
import com.sun.jdi.Mirror
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveType
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.ShortType
import com.sun.jdi.ThreadReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private suspend fun Value.booleanValue(): Boolean {
    if (this is PrimitiveValue) {
        return this.booleanValue()
    }

    if (this is ObjectReference) {
        val type = this.referenceType()
        if (type.name() == JdiTypes.BOOLEAN) {
            val returnedValue = withContext(Dispatchers.IO) {
                this@booleanValue.invokeMethod(
                    this@booleanValue.owningThread(),
                    type.methodsByName("booleanValue", "()Z").first(),
                    emptyList(),
                    ThreadReference.INVOKE_SINGLE_THREADED
                )
            }

            return returnedValue.booleanValue()
        }
    }

    throw IllegalArgumentException("Value is not a boolean: $this")
}

object VariableValues {

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
        mirror: Mirror,
        type: Type,
        value: String
    ): Value? = when (type) {
        is BooleanType -> value.toBooleanStrictOrNull()?.let(mirror::mirrorOf)
        is ByteType -> value.toByteOrNull()?.let(mirror::mirrorOf)
        is CharType -> value.singleOrNull()?.let(mirror::mirrorOf)
        is ShortType -> value.toShortOrNull()?.let(mirror::mirrorOf)
        is IntegerType -> value.toIntOrNull()?.let(mirror::mirrorOf)
        is LongType -> value.toLongOrNull()?.let(mirror::mirrorOf)
        is FloatType -> value.toFloatOrNull()?.let(mirror::mirrorOf)
        is DoubleType -> value.toDoubleOrNull()?.let(mirror::mirrorOf)

        // creating new reference types requires a new mirror to be created in the remote VM
        // hence, communication is required over the socket connection, which should be performed
        // on the IO dispatcher
        is ReferenceType -> withContext(Dispatchers.IO) {
            when (type.name()) {
                JdiTypes.BOOLEAN -> value.toBooleanStrictOrNull()?.let(mirror::mirrorOf)
                JdiTypes.BYTE -> value.toByteOrNull()?.let(mirror::mirrorOf)
                JdiTypes.CHARACTER -> value.singleOrNull()?.let(mirror::mirrorOf)
                JdiTypes.SHORT -> value.toShortOrNull()?.let(mirror::mirrorOf)
                JdiTypes.INTEGER -> value.toIntOrNull()?.let(mirror::mirrorOf)
                JdiTypes.LONG -> value.toLongOrNull()?.let(mirror::mirrorOf)
                JdiTypes.FLOAT -> value.toFloatOrNull()?.let(mirror::mirrorOf)
                JdiTypes.DOUBLE -> value.toDoubleOrNull()?.let(mirror::mirrorOf)
                JdiTypes.STRING -> value.let(mirror::mirrorOf)
                else -> null
            }
        }

        else -> null
    }
}

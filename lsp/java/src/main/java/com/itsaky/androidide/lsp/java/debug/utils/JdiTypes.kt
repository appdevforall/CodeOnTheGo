package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.java.debug.JavaDebugAdapter
import com.sun.jdi.ClassType
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val JAVA_LANG = "java.lang"

internal enum class BoxedPrimitive(
    name: String,
    val internalPrimitiveName: String,
) {
    BOOLEAN("Boolean", "Z"),
    BYTE("Byte", "B"),
    CHARACTER("Character", "C"),
    SHORT("Short", "S"),
    INTEGER("Integer", "I"),
    LONG("Long", "J"),
    FLOAT("Float", "F"),
    DOUBLE("Double", "D")
    ;

    val typeName: String = "$JAVA_LANG.$name"
    val internalTypeName = "L${typeName.replace('.', '/')};"
}

internal object JdiTypes {
    const val BOOLEAN = "$JAVA_LANG.Boolean"
    const val BYTE = "$JAVA_LANG.Byte"
    const val SHORT = "$JAVA_LANG.Short"
    const val CHARACTER = "$JAVA_LANG.Character"
    const val INTEGER = "$JAVA_LANG.Integer"
    const val LONG = "$JAVA_LANG.Long"
    const val FLOAT = "$JAVA_LANG.Float"
    const val DOUBLE = "$JAVA_LANG.Double"
    const val STRING = "$JAVA_LANG.String"

    suspend fun boxedPrimitive(vm: VirtualMachine, primitive: BoxedPrimitive) =
        type(vm, primitive.typeName)

    private suspend fun type(vm: VirtualMachine, name: String): ReferenceType? =
        withContext(Dispatchers.IO) {
            // TODO: Maybe cache this per VM?
            vm.classesByName(name).firstOrNull()
        }

    suspend fun createBoxedPrimitive(
        thread: ThreadReference,
        primitive: BoxedPrimitive,
        value: Value,
    ): Result<Value?> = runCatching {
        val type = boxedPrimitive(thread.virtualMachine(), primitive)
            ?: throw RuntimeException("Boxed primitive type not found '${primitive.typeName}'")

        if (type !is ClassType) {
            throw RuntimeException("Boxed primitive type is not a class type '${primitive.typeName}'")
        }

        val name = "valueOf"
        val signature = "(${primitive.internalPrimitiveName})${primitive.internalTypeName}"

        val evalContext = JavaDebugAdapter.requireInstance().evalContext()
        val method = type.methodsByName(name, signature).firstOrNull()
            ?: throw RuntimeException("Method not found: ${name}${signature} in ${type.name()}")
        val result = evalContext.evaluate(thread) {
            type.invokeMethod(
                thread,
                method,
                listOf(value),
                ObjectReference.INVOKE_SINGLE_THREADED
            )
        }

        if (result == null) {
            throw RuntimeException("An error invoking $name$signature in ${type.name()}, or the method returned null")
        }

        result
    }
}
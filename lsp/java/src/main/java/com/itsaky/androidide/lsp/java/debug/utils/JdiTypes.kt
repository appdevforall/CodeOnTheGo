package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object JdiTypes {
    private const val JAVA_LANG = "java.lang"
    const val OBJECT = "$JAVA_LANG.Object"
    const val BOOLEAN = "$JAVA_LANG.Boolean"
    const val BYTE = "$JAVA_LANG.Byte"
    const val SHORT = "$JAVA_LANG.Short"
    const val CHARACTER = "$JAVA_LANG.Character"
    const val INTEGER = "$JAVA_LANG.Integer"
    const val LONG = "$JAVA_LANG.Long"
    const val FLOAT = "$JAVA_LANG.Float"
    const val DOUBLE = "$JAVA_LANG.Double"
    const val VOID = "$JAVA_LANG.Void"
    const val STRING = "$JAVA_LANG.String"

    suspend fun objectType(vm: VirtualMachine): ReferenceType = type(vm, OBJECT)!!
    suspend fun boxedBoolean(vm: VirtualMachine): ReferenceType = type(vm, BOOLEAN)!!
    suspend fun boxedByte(vm: VirtualMachine): ReferenceType = type(vm, BYTE)!!
    suspend fun boxedChar(vm: VirtualMachine): ReferenceType = type(vm, CHARACTER)!!
    suspend fun boxedShort(vm: VirtualMachine): ReferenceType = type(vm, SHORT)!!
    suspend fun boxedInt(vm: VirtualMachine): ReferenceType = type(vm, INTEGER)!!
    suspend fun boxedLong(vm: VirtualMachine): ReferenceType = type(vm, LONG)!!
    suspend fun boxedFloat(vm: VirtualMachine): ReferenceType = type(vm, FLOAT)!!
    suspend fun boxedDouble(vm: VirtualMachine): ReferenceType = type(vm, DOUBLE)!!
    suspend fun boxedString(vm: VirtualMachine): ReferenceType = type(vm, STRING)!!

    private suspend fun type(vm: VirtualMachine, name: String): ReferenceType? = withContext(Dispatchers.IO) {
        // TODO: Maybe cache this per VM?
        vm.classesByName(name).firstOrNull()
    }
}
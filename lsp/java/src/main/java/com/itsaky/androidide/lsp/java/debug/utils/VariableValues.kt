package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.DoubleValue
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.ShortValue
import com.sun.jdi.VirtualMachine

fun VirtualMachine.booleanValue(str: String): BooleanValue? =
    str.toBooleanStrictOrNull()?.let(::mirrorOf)

fun VirtualMachine.byteValue(str: String): ByteValue? =
    str.toByteOrNull()?.let(::mirrorOf)

fun VirtualMachine.charValue(str: String): CharValue? =
    str.takeIf { it.length == 1 }?.getOrNull(0)?.let(::mirrorOf)

fun VirtualMachine.shortValue(str: String): ShortValue? =
    str.toShortOrNull()?.let(::mirrorOf)

fun VirtualMachine.intValue(str: String): IntegerValue? =
    str.toIntOrNull()?.let(::mirrorOf)

fun VirtualMachine.longValue(str: String): LongValue? =
    str.toLongOrNull()?.let(::mirrorOf)

fun VirtualMachine.floatValue(str: String): FloatValue? =
    str.toFloatOrNull()?.let(::mirrorOf)

fun VirtualMachine.doubleValue(str: String): DoubleValue? =
    str.toDoubleOrNull()?.let(::mirrorOf)


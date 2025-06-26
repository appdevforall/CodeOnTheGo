package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.BooleanType
import com.sun.jdi.ByteType
import com.sun.jdi.CharType
import com.sun.jdi.DoubleType
import com.sun.jdi.FloatType
import com.sun.jdi.IntegerType
import com.sun.jdi.LongType
import com.sun.jdi.Mirror
import com.sun.jdi.ShortType
import com.sun.jdi.StringReference
import com.sun.jdi.Type
import com.sun.jdi.Value

fun Mirror.parseValue(
    type: Type,
    value: String
): Value? = when (type) {
    is BooleanType -> value.toBooleanStrictOrNull()?.let(this::mirrorOf)
    is ByteType -> value.toByteOrNull()?.let(this::mirrorOf)
    is CharType -> value.singleOrNull()?.let(this::mirrorOf)
    is ShortType -> value.toShortOrNull()?.let(this::mirrorOf)
    is IntegerType -> value.toIntOrNull()?.let(this::mirrorOf)
    is LongType -> value.toLongOrNull()?.let(this::mirrorOf)
    is FloatType -> value.toFloatOrNull()?.let(this::mirrorOf)
    is DoubleType -> value.toDoubleOrNull()?.let(this::mirrorOf)
    is StringReference -> mirrorOf(value)
    else -> null
}


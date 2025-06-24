package com.itsaky.androidide.lsp.debug.utils

import com.itsaky.androidide.lsp.debug.model.InputValueKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.VariableKind

fun isValid(value: String, type: InputValueKind): Boolean {
    return when (type.kind) {
        VariableKind.PRIMITIVE -> {
            when (type.primitiveKind) {
                PrimitiveKind.BOOLEAN -> value.equals("true", true) || value.equals("false", true)
                PrimitiveKind.BYTE -> value.toByteOrNull() != null
                PrimitiveKind.SHORT -> value.toShortOrNull() != null
                PrimitiveKind.INT -> value.toIntOrNull() != null
                PrimitiveKind.LONG -> value.toLongOrNull() != null
                PrimitiveKind.FLOAT -> value.toFloatOrNull() != null
                PrimitiveKind.DOUBLE -> value.toDoubleOrNull() != null
                PrimitiveKind.CHAR -> value.length == 1
                null -> false
            }
        }
        VariableKind.STRING -> isAStringValidate(value)
        VariableKind.ARRAYLIKE -> value.startsWith("[") && value.endsWith("]")
        VariableKind.REFERENCE -> value.isNotBlank()
        VariableKind.UNKNOWN -> false
    }
}

private fun isAStringValidate(value: String): Boolean {
    val trimmed = value.trim()

    val blacklist = listOf("<script", "</script", "javascript:", "onerror", "onload", "eval(", "alert(")
    if (blacklist.any { it in trimmed.lowercase() }) return false

    val regex = Regex("^[a-zA-Z0-9 ]+$")
    if (!regex.matches(trimmed)) return false
    return true
}

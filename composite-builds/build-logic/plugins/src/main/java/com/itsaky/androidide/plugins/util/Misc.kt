package com.itsaky.androidide.plugins.util

import com.android.build.api.variant.Variant
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale

fun MessageDigest.sha256(file: File): String {
    this.reset()

    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val input = file.inputStream()
    while (true) {
        val bytesRead = input.read(buffer)
        if (bytesRead <= 0) break
        update(buffer, 0, bytesRead)
    }

    var checksum = BigInteger(1, digest()).toString(16)
    if (checksum.length < 64) {
        checksum = "0".repeat(64 - checksum.length) + checksum
    }

    return checksum
}

fun Variant.capitalizedName() =
    name.capitalized()

fun String.capitalized() =
    replaceFirstChar {
        if (it.isLowerCase()) {
            it.titlecase(
                Locale.ROOT,
            )
        } else {
            it.toString()
        }
    }

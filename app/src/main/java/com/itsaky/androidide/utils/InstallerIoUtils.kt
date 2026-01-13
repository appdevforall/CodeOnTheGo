package com.itsaky.androidide.utils

import android.content.Context
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal inline fun <T> withTempZipChannel(
    stagingDir: Path,
    prefix: String,
    writeTo: (Path) -> Unit,
    useChannel: (SeekableByteChannel) -> T,
): T {
    val tempZipPath = Files.createTempFile(stagingDir, prefix, ".zip")
    try {
        writeTo(tempZipPath)
        Files.newByteChannel(tempZipPath).use { ch ->
            return useChannel(ch)
        }
    } finally {
        Files.deleteIfExists(tempZipPath)
    }
}

internal fun writeBrotliAssetToPath(
    context: Context,
    assetPath: String,
    destPath: Path,
) {
    context.assets.open(assetPath).use { assetStream ->
        BrotliInputStream(assetStream).use { brotli ->
            Files.newOutputStream(destPath).use { output ->
                brotli.copyTo(output)
            }
        }
    }
}

internal inline fun <T> retryOnceOnNoSuchFile(
    onFirstFailure: () -> Unit = {},
    onSecondFailure: (NoSuchFileException) -> Nothing,
    block: () -> T,
): T {
    return try {
        block()
    } catch (_: NoSuchFileException) {
        onFirstFailure()
        try {
            block()
        } catch (e2: NoSuchFileException) {
            onSecondFailure(e2)
        }
    }
}

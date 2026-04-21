package com.itsaky.androidide.plugins.manager.documentation

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder
import java.io.ByteArrayOutputStream

internal object BrotliCompressor {

    private val params: Encoder.Parameters by lazy {
        Brotli4jLoader.ensureAvailability()
        Encoder.Parameters().setQuality(11).setWindow(24)
    }

    fun compress(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        BrotliOutputStream(out, params).use { it.write(input) }
        return out.toByteArray()
    }
}

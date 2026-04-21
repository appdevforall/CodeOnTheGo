package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.kotlin.analysis.api.platform.KotlinDeserializedDeclarationsOrigin
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings

class PlatformSettings : KotlinPlatformSettings {
	override val deserializedDeclarationsOrigin: KotlinDeserializedDeclarationsOrigin
		get() = KotlinDeserializedDeclarationsOrigin.BINARIES
}
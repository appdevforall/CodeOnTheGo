package org.appdevforall.codeonthego.models

data class StorageInfo(
    val isLowStorage: Boolean,
    val availableBytes: Long,
    val additionalBytesNeeded: Long
)

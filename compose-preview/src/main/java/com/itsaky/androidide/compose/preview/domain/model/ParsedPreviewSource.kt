package com.itsaky.androidide.compose.preview.domain.model

import com.itsaky.androidide.compose.preview.PreviewConfig

data class ParsedPreviewSource(
    val packageName: String,
    val className: String?,
    val previewConfigs: List<PreviewConfig>
)

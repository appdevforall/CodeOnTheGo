package com.itsaky.androidide.lsp.kotlin.utils

import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.KaTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.types.Variance

@OptIn(KaExperimentalApi::class, KaContextParameterApi::class)
internal fun KaSession.renderName(
	type: KaType,
	renderer: KaTypeRenderer = KaTypeRendererForSource.WITH_SHORT_NAMES,
	position: Variance = Variance.INVARIANT,
): String =
	type.run {
		render(renderer, position)
	}

package org.appdevforall.codeonthego.plugins.base

@RequiresOptIn(
	level = RequiresOptIn.Level.ERROR,
	message = "Host-internal plugin API: only the IDE host (plugin manager) may call it, not plugins.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class InternalPluginApi

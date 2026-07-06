package org.appdevforall.cotg.profiler.model

data class ProcessInfo(
	val pid: Int,
	val packageName: String,
	val label: String,
	val debuggable: Boolean,
	val profileable: Boolean,
)

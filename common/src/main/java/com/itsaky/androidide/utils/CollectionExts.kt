package com.itsaky.androidide.utils

fun <E, T: Collection<E>> T.ifNotEmpty(action: T.() -> Unit) {
	if (isNotEmpty()) action()
}

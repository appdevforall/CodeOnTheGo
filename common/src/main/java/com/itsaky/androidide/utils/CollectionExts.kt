package com.itsaky.androidide.utils

inline fun <E, T: Collection<E>> T.ifNotEmpty(crossinline action: T.() -> Unit) {
	if (isNotEmpty()) action()
}

package moe.shizuku.manager.utils

fun <T> unsafeLazy(initializer: () -> T): Lazy<T> = kotlin.lazy(LazyThreadSafetyMode.NONE, initializer)

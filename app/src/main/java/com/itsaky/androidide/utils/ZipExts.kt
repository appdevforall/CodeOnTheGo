package com.itsaky.androidide.utils

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

inline fun ZipInputStream.forEach(crossinline action: (ZipEntry) -> Unit) {
    var entry: ZipEntry?
    while (nextEntry.also { entry = it } != null) {
        action(entry!!)
    }
}

inline fun ZipInputStream.useEntriesEach(crossinline action: (ZipInputStream, ZipEntry) -> Unit) = use {
    forEach { entry ->
        action(this, entry)
    }
}

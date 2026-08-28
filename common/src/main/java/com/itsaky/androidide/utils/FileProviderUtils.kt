package com.itsaky.androidide.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

const val FILE_PROVIDER_AUTHORITY_SUFFIX = "providers.fileprovider"

/**
 * This app's [androidx.core.content.FileProvider] authority for a given [packageName] - shared so
 * every caller that mints or checks a `content://` Uri against it agrees on the same string, even
 * callers (e.g. a ViewModel) that only hold a package name rather than a full [Context].
 */
fun fileProviderAuthorityFor(packageName: String): String = "$packageName.$FILE_PROVIDER_AUTHORITY_SUFFIX"

/**
 * This app's [androidx.core.content.FileProvider] authority - shared so every caller that mints
 * or checks a `content://` Uri against it agrees on the same string.
 */
fun Context.fileProviderAuthority(): String = fileProviderAuthorityFor(packageName)

/** Mints a `content://` Uri for [file] via this app's [androidx.core.content.FileProvider]. */
fun Context.fileProviderUriFor(file: File): Uri = FileProvider.getUriForFile(this, fileProviderAuthority(), file)

package com.itsaky.androidide.utils

private const val BYTES_IN_GIGABYTE = 1024L * 1024L * 1024L

/** Converts a Long representing bytes into Gigabytes. */
fun Long.bytesToGigabytes(): Long = this / BYTES_IN_GIGABYTE

/** Converts a Long representing Gigabytes into bytes. */
fun Long.gigabytesToBytes(): Long = this * BYTES_IN_GIGABYTE

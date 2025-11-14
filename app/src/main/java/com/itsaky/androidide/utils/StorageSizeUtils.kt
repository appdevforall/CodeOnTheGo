package com.itsaky.androidide.utils

private const val BYTES_IN_GIGABYTE = 1024F * 1024F * 1024F

/** Converts a Long representing bytes into Gigabytes. */
fun Float.bytesToGigabytes(): Float = this / BYTES_IN_GIGABYTE

/** Converts a Long representing Gigabytes into bytes. */
fun Long.gigabytesToBytes(): Float = this * BYTES_IN_GIGABYTE

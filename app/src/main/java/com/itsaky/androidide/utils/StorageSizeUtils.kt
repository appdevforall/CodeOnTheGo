package com.itsaky.androidide.utils

import kotlin.math.roundToLong

/**
 * Using binary prefix (1024^3) for Gibibytes (GiB) to be consistent with how Android's file
 * system APIs report storage space. While storage manufacturers use decimal
 * Gigabytes (GB, 1000^3), OSes use binary.
 * */
private const val BYTES_IN_GIGABYTE = 1024F * 1024F * 1024F

/** Converts a Long representing bytes into Gigabytes. */
fun Long.bytesToGigabytes(): Float = this / BYTES_IN_GIGABYTE

/** Converts a Long representing Gigabytes into bytes. */
fun Long.gigabytesToBytes(): Long = (this * BYTES_IN_GIGABYTE).roundToLong()

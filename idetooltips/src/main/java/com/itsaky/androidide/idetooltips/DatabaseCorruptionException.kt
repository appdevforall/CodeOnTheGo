package com.itsaky.androidide.idetooltips

/**
 * Exception thrown when the database contains corrupted data.
 * This typically indicates multiple rows exist where only one should exist,
 * suggesting data integrity issues in the database.
 */
class DatabaseCorruptionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) 
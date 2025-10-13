package com.itsaky.androidide.idetooltips

/**
 * Exception thrown when a tooltip is requested but not found in the database.
 * This typically indicates a bad request - the category/tag combination doesn't exist.
 */
class NoTooltipFoundException(
    category: String,
    tag: String
) : Exception("No tooltip found for category='$category', tag='$tag'") 
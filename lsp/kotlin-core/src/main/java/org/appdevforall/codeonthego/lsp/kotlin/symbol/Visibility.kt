package org.appdevforall.codeonthego.lsp.kotlin.symbol

/**
 * Kotlin visibility modifiers.
 *
 * Visibility controls which code can access a declaration. Kotlin has four visibility levels:
 *
 * - [PUBLIC]: Visible everywhere (default for most declarations)
 * - [PRIVATE]: Visible only within the containing declaration
 * - [PROTECTED]: Visible within the class and its subclasses
 * - [INTERNAL]: Visible within the same module
 *
 * ## Default Visibility
 *
 * The default visibility depends on the declaration context:
 * - Top-level declarations: [PUBLIC]
 * - Class members: [PUBLIC]
 * - Local declarations: No visibility modifier applies
 *
 * ## Visibility Rules
 *
 * - [PROTECTED] is only valid for class members, not top-level declarations
 * - [PRIVATE] at top-level means visible within the file
 * - [PRIVATE] in a class means visible within the class
 * - [INTERNAL] is visible within the same Kotlin module (compilation unit)
 *
 * @property keyword The Kotlin keyword for this visibility
 * @property ordinal Lower ordinal = more restrictive
 */
enum class Visibility(val keyword: String) {
    /**
     * Visible only within the containing declaration.
     *
     * For top-level declarations: visible within the same file.
     * For class members: visible within the class body.
     */
    PRIVATE("private"),

    /**
     * Visible within the class and its subclasses.
     *
     * Only valid for class members. Cannot be applied to top-level declarations.
     */
    PROTECTED("protected"),

    /**
     * Visible within the same module.
     *
     * A module is a set of Kotlin files compiled together:
     * - An IntelliJ IDEA module
     * - A Maven project
     * - A Gradle source set
     */
    INTERNAL("internal"),

    /**
     * Visible everywhere.
     *
     * This is the default visibility for most declarations.
     */
    PUBLIC("public");

    /**
     * Checks if this visibility is at least as permissive as another.
     *
     * PUBLIC >= INTERNAL >= PROTECTED >= PRIVATE
     */
    fun isAtLeast(other: Visibility): Boolean = this.ordinal >= other.ordinal

    /**
     * Checks if this visibility is more restrictive than another.
     */
    fun isMoreRestrictiveThan(other: Visibility): Boolean = this.ordinal < other.ordinal

    companion object {
        /**
         * The default visibility for declarations without an explicit modifier.
         */
        val DEFAULT: Visibility = PUBLIC

        /**
         * Parses a visibility from its keyword.
         *
         * @param keyword The visibility keyword (e.g., "private", "public")
         * @return The corresponding [Visibility], or null if not recognized
         */
        fun fromKeyword(keyword: String): Visibility? {
            return entries.find { it.keyword == keyword }
        }
    }
}

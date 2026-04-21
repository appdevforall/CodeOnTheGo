package com.itsaky.androidide.git.core

import android.util.Log
import org.eclipse.jgit.transport.URIish
import java.net.URISyntaxException

/**
 * Parses and validates a raw string into a Git repository URL.
 *
 * This method leverages JGit's [URIish] to handle various Git URL formats,
 * including SCP-like SSH URLs. It enforces the presence of either a host or
 * a scheme to prevent plain clipboard text from being incorrectly treated as a valid URI.
 *
 * @param rawText The raw string input to be parsed.
 * @return The normalized Git URL string, or null if the input is invalid or parsing fails.
 */
fun parseGitRepositoryUrl(rawText: String): String? {
    val candidate = rawText.trim()
    if (candidate.isBlank()) return null

    return try {
        val uri = URIish(candidate)

        if (uri.host != null || uri.scheme != null) {
            uri.toString()
        } else {
            null
        }
    } catch (e: URISyntaxException) {
        Log.w("GitParser", "Failed to parse Git URL candidate: $candidate", e)
        null
    }
}

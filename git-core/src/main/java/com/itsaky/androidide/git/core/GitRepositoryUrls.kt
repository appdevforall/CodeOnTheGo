package com.itsaky.androidide.git.core

import java.net.URI
import java.net.URISyntaxException

private val sshGitUrlRegex = Regex("^[^\\s@/]+@[^\\s:/]+:\\S+$")
private val supportedGitSchemes = setOf("http", "https", "git", "ssh")

fun parseGitRepositoryUrl(rawText: String): String? {
    val candidate = rawText.trim()
    if (candidate.isBlank()) {
        return null
    }

    if (candidate.matches(sshGitUrlRegex)) {
        return candidate
    }

    val uri = try {
        URI(candidate)
    } catch (_: URISyntaxException) {
        return null
    }

    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in supportedGitSchemes) {
        return null
    }

    val host = uri.host
    if (host.isNullOrBlank()) {
        return null
    }

    val path = uri.path ?: ""
    val pathSegments = path.split("/").filter { it.isNotBlank() }

    val isExplicitGitUrl = path.endsWith(".git")
    val webUiIndicators = setOf("tree", "blob", "raw", "commits", "commit", "pull", "issues", "releases", "tags", "branches", "-")
    val hasWebUiSegments = pathSegments.any { it in webUiIndicators }

    if (!isExplicitGitUrl && (pathSegments.size < 2 || hasWebUiSegments || !uri.query.isNullOrBlank())) {
        return null
    }

    return try {
        URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            uri.path,
            if (isExplicitGitUrl) uri.query else null,
            null
        ).toString()
    } catch (_: URISyntaxException) {
        null
    }
}

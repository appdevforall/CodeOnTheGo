package com.itsaky.androidide.git.core

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GitRepositoryUrlsTest {

    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // --- blank / empty inputs ---

    @Test
    fun `blank string returns null`() {
        assertThat(parseGitRepositoryUrl("")).isNull()
    }

    @Test
    fun `whitespace-only string returns null`() {
        assertThat(parseGitRepositoryUrl("   ")).isNull()
    }

    @Test
    fun `newline-only string returns null`() {
        assertThat(parseGitRepositoryUrl("\n\t")).isNull()
    }

    // --- valid HTTPS URLs ---

    @Test
    fun `valid HTTPS GitHub URL is accepted`() {
        val result = parseGitRepositoryUrl("https://github.com/user/repo.git")
        assertThat(result).isNotNull()
        assertThat(result).contains("github.com")
    }

    @Test
    fun `valid HTTPS URL with trailing whitespace is trimmed and accepted`() {
        val result = parseGitRepositoryUrl("  https://github.com/user/repo.git  ")
        assertThat(result).isNotNull()
    }

    @Test
    fun `HTTPS URL without git suffix is accepted`() {
        val result = parseGitRepositoryUrl("https://github.com/user/repo")
        assertThat(result).isNotNull()
        assertThat(result).contains("github.com")
    }

    @Test
    fun `HTTPS URL with port is accepted`() {
        val result = parseGitRepositoryUrl("https://mygitserver.example.com:8443/project/repo.git")
        assertThat(result).isNotNull()
    }

    @Test
    fun `HTTP URL is accepted`() {
        val result = parseGitRepositoryUrl("http://example.com/repo.git")
        assertThat(result).isNotNull()
    }

    // --- valid SSH URLs ---

    @Test
    fun `SSH URL with scheme is accepted`() {
        val result = parseGitRepositoryUrl("ssh://git@github.com/user/repo.git")
        assertThat(result).isNotNull()
        assertThat(result).contains("github.com")
    }

    @Test
    fun `SCP-style SSH URL is accepted`() {
        val result = parseGitRepositoryUrl("git@github.com:user/repo.git")
        assertThat(result).isNotNull()
        assertThat(result).contains("github.com")
    }

    @Test
    fun `SCP-style SSH URL for GitLab is accepted`() {
        val result = parseGitRepositoryUrl("git@gitlab.com:group/project.git")
        assertThat(result).isNotNull()
        assertThat(result).contains("gitlab.com")
    }

    @Test
    fun `SCP-style SSH URL with subdomain is accepted`() {
        val result = parseGitRepositoryUrl("git@bitbucket.org:team/repo.git")
        assertThat(result).isNotNull()
    }

    // --- git:// protocol ---

    @Test
    fun `git protocol URL is accepted`() {
        val result = parseGitRepositoryUrl("git://github.com/user/repo.git")
        assertThat(result).isNotNull()
        assertThat(result).contains("github.com")
    }

    // --- inputs that should return null (no host and no scheme) ---

    @Test
    fun `plain word without host or scheme returns null`() {
        assertThat(parseGitRepositoryUrl("notaurl")).isNull()
    }

    @Test
    fun `relative path returns null`() {
        assertThat(parseGitRepositoryUrl("some/relative/path")).isNull()
    }

    @Test
    fun `random clipboard text returns null`() {
        assertThat(parseGitRepositoryUrl("Hello, copy this link")).isNull()
    }

    @Test
    fun `dot-only string returns null`() {
        assertThat(parseGitRepositoryUrl(".")).isNull()
    }

    // --- file:// scheme ---

    @Test
    fun `file scheme URL is accepted`() {
        val result = parseGitRepositoryUrl("file:///home/user/my-repo")
        assertThat(result).isNotNull()
    }
}

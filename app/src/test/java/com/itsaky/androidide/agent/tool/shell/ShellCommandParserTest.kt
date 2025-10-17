package com.itsaky.androidide.agent.tool.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandParserTest {

    @Test
    fun `parse read command`() {
        val command = "cat file1.txt file2.txt"
        val parsed = ShellCommandParser.parse(
            ShellCommandParser.tokenize(command),
            command
        )

        assertTrue(parsed is ParsedCommand.Read)
        parsed as ParsedCommand.Read
        assertEquals(listOf("file1.txt", "file2.txt"), parsed.files)
    }

    @Test
    fun `parse list command with path`() {
        val command = "ls -la src/dir"
        val parsed = ShellCommandParser.parse(
            ShellCommandParser.tokenize(command),
            command
        )

        assertTrue(parsed is ParsedCommand.ListFiles)
        parsed as ParsedCommand.ListFiles
        assertEquals("src/dir", parsed.path)
    }

    @Test
    fun `parse list command at root`() {
        val command = "ls /"
        val parsed = ShellCommandParser.parse(
            ShellCommandParser.tokenize(command),
            command
        )

        assertTrue(parsed is ParsedCommand.ListFiles)
        parsed as ParsedCommand.ListFiles
        assertEquals("/", parsed.path)
    }

    @Test
    fun `parse search command with path`() {
        val command = "rg searchTerm src/dir"
        val parsed = ShellCommandParser.parse(
            ShellCommandParser.tokenize(command),
            command
        )

        assertTrue(parsed is ParsedCommand.Search)
        parsed as ParsedCommand.Search
        assertEquals("searchTerm", parsed.query)
        assertEquals("src/dir", parsed.path)
    }

    @Test
    fun `parse search command without path`() {
        val command = "grep term"
        val parsed = ShellCommandParser.parse(
            ShellCommandParser.tokenize(command),
            command
        )

        assertTrue(parsed is ParsedCommand.Search)
        parsed as ParsedCommand.Search
        assertEquals("term", parsed.query)
        assertEquals(null, parsed.path)
    }

    @Test
    fun `parse unknown command`() {
        val command = "git status"
        val parsed = ShellCommandParser.parse(
            ShellCommandParser.tokenize(command),
            command
        )

        assertTrue(parsed is ParsedCommand.Unknown)
    }

    @Test
    fun `parse complex command as unknown`() {
        val tokens = listOf("find", ".", "-name", "*.kt", "|", "xargs", "grep", "foo")
        val command = tokens.joinToString(" ")
        val parsed = ShellCommandParser.parse(tokens, command)

        assertTrue(parsed is ParsedCommand.Unknown)
    }
}

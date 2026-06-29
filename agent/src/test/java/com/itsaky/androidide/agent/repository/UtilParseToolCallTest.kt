package com.itsaky.androidide.agent.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class UtilParseToolCallTest {

    private val allToolKeys = setOf(
        "list_files", "read_file", "search_project",
        "create_file", "update_file", "add_dependency", "get_current_datetime"
    )

    // --- Happy path: clean JSON inside <tool_call> tags ---

    @Test
    fun `parses tool call with name and empty args from tool_call tags`() {
        val input = """<tool_call>{"name": "get_current_datetime", "args": {}}</tool_call>"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("get_current_datetime", result!!.name)
        assertEquals(emptyMap<String, String>(), result.args)
    }

    @Test
    fun `parses tool call with args from tool_call tags`() {
        val input = """<tool_call>{"name": "read_file", "args": {"file_path": "/src/main/Foo.kt"}}</tool_call>"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("read_file", result!!.name)
        assertEquals("/src/main/Foo.kt", result.args["file_path"])
    }

    @Test
    fun `parses tool call for list_files with path arg`() {
        val input = """<tool_call>{"name": "list_files", "args": {"path": "/src"}}</tool_call>"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("list_files", result!!.name)
        assertEquals("/src", result.args["path"])
    }

    @Test
    fun `parses tool call for search_project`() {
        val input = """<tool_call>{"name": "search_project", "args": {"query": "MainActivity"}}</tool_call>"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("search_project", result!!.name)
        assertEquals("MainActivity", result.args["query"])
    }

    // --- Happy path: JSON without tool_call wrapper ---

    @Test
    fun `parses bare JSON object with name field`() {
        val input = """{"name": "list_files", "args": {"path": "."}}"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("list_files", result!!.name)
    }

    @Test
    fun `parses JSON wrapped in markdown code fence`() {
        val input = "```json\n{\"name\": \"read_file\", \"args\": {\"file_path\": \"/README.md\"}}\n```"
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("read_file", result!!.name)
        assertEquals("/README.md", result.args["file_path"])
    }

    @Test
    fun `parses JSON wrapped in markdown code fence without json label`() {
        val input = "```\n{\"name\": \"list_files\", \"args\": {\"path\": \"/\"}}\n```"
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("list_files", result!!.name)
    }

    // --- Name aliases ---

    @Test
    fun `list_dir is resolved to list_files`() {
        val input = """<tool_call>{"name": "list_dir", "args": {"path": "/"}}</tool_call>"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("list_files", result!!.name)
    }

    // --- tool_name alternative field ---

    @Test
    fun `accepts tool_name field as alternative to name`() {
        val input = """{"tool_name": "list_files", "args": {"path": "."}}"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("list_files", result!!.name)
    }

    // --- Tool-only tag (model emits just the name inside tags) ---

    @Test
    fun `parses tool-only tag with no args`() {
        val input = "<tool_call>get_current_datetime</tool_call>"
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("get_current_datetime", result!!.name)
    }

    // --- Failure cases: unknown tool ---

    @Test
    fun `returns null when tool name is not in available tools`() {
        val input = """{"name": "unknown_tool", "args": {}}"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNull(result)
    }

    @Test
    fun `returns null when name field is missing`() {
        val input = """{"args": {"path": "/"}}"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNull(result)
    }

    @Test
    fun `returns null when name field is blank`() {
        val input = """{"name": "", "args": {}}"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNull(result)
    }

    // --- Failure cases: empty or unrecognizable input ---

    @Test
    fun `returns null for empty string`() {
        val result = Util.parseToolCall("", allToolKeys)
        assertNull(result)
    }

    @Test
    fun `returns null for plain prose`() {
        val result = Util.parseToolCall("Sure! I will list the files for you.", allToolKeys)
        assertNull(result)
    }

    @Test
    fun `returns null when no tools are provided and response has a tool call`() {
        val input = """{"name": "list_files", "args": {}}"""
        val result = Util.parseToolCall(input, emptySet())
        assertNull(result)
    }

    // --- JSON with trailing prose (model hallucinates follow-on text) ---

    @Test
    fun `extracts first JSON object and ignores trailing text`() {
        val input = """{"name": "read_file", "args": {"file_path": "/Foo.kt"}} Let me read that file now."""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("read_file", result!!.name)
    }

    // --- Args with multiple values ---

    @Test
    fun `parses multiple args correctly`() {
        val input = """<tool_call>{"name": "create_file", "args": {"path": "/new.kt", "content": "class Foo {}"}}</tool_call>"""
        val result = Util.parseToolCall(input, allToolKeys)
        assertNotNull(result)
        assertEquals("create_file", result!!.name)
        assertEquals("/new.kt", result.args["path"])
        assertEquals("class Foo {}", result.args["content"])
    }

    // --- Empty tool set ---

    @Test
    fun `returns null when tool set is empty and input has JSON`() {
        val input = """{"name": "list_files", "args": {}}"""
        val result = Util.parseToolCall(input, emptySet())
        assertNull(result)
    }
}

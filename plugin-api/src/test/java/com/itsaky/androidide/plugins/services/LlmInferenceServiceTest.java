package com.itsaky.androidide.plugins.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Covers the validating, coalescing and copying branches of the value types plugins construct. Every consumer of this jar is an out-of-tree plugin, so a constructor that accepts a bad state here surfaces as a runtime failure nothing in this repo compiles against.
 */
public class LlmInferenceServiceTest {

	@Test
	public void chatMessageCarriesNoCorrelatorsForAConversationTurn() {
		LlmInferenceService.ChatMessage message = new LlmInferenceService.ChatMessage(LlmInferenceService.ChatMessage.Role.USER, "hello");

		assertEquals(LlmInferenceService.ChatMessage.Role.USER, message.role);
		assertEquals("hello", message.content);
		assertNull(message.toolCallId);
		assertNull(message.toolName);
	}

	@Test
	public void chatMessageRejectsANullRole() {
		try {
			new LlmInferenceService.ChatMessage(null, "hello");
			fail("expected NullPointerException");
		} catch (NullPointerException expected) {
			// the role is what selects the shape; a null one has no shape
		}
	}

	@Test
	public void chatMessageRejectsAToolRoleWithoutCorrelators() {
		try {
			new LlmInferenceService.ChatMessage(LlmInferenceService.ChatMessage.Role.TOOL, "result");
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("toolResult"));
		}
	}

	@Test
	public void llmConfigRejectsAMissingBackendId() {
		try {
			new LlmInferenceService.LlmConfig(null);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("backendId"));
		}
	}

	@Test
	public void llmResponseFailureCarriesErrorAndNoText() {
		LlmInferenceService.LlmResponse response = LlmInferenceService.LlmResponse.failure("no model");

		assertFalse(response.success);
		assertNull(response.text);
		assertEquals("no model", response.error);
	}

	@Test
	public void llmResponseSuccessCarriesTextAndNoError() {
		LlmInferenceService.LlmResponse response = LlmInferenceService.LlmResponse.success("done", 12, 340L);

		assertTrue(response.success);
		assertEquals("done", response.text);
		assertNull(response.error);
		assertEquals(12, response.tokensGenerated);
		assertEquals(340L, response.timeMs);
	}

	@Test
	public void systemPromptRequestAcceptsNoCallSyntax() {
		LlmInferenceService.SystemPromptRequest request = new LlmInferenceService.SystemPromptRequest(Collections.emptyList(), null, null);

		assertNull(request.toolCallSyntax);
		assertNull(request.exampleFilePath);
	}

	@Test
	public void systemPromptRequestCoalescesNullToolsToAnEmptyList() {
		LlmInferenceService.SystemPromptRequest request = new LlmInferenceService.SystemPromptRequest(null, "<tool_call>", "app/src/Main.kt");

		assertTrue(request.tools.isEmpty());
	}

	@Test
	public void systemPromptRequestCopiesTheToolList() {
		List<LlmInferenceService.ToolDefinition> tools = new ArrayList<>();
		tools.add(new LlmInferenceService.ToolDefinition("read_file", "Reads a file", null));

		LlmInferenceService.SystemPromptRequest request = new LlmInferenceService.SystemPromptRequest(tools, "<tool_call>", null);
		tools.clear();

		assertEquals(1, request.tools.size());
		assertEquals("read_file", request.tools.get(0).name);
	}

	@Test
	public void systemPromptRequestPublishesAnUnmodifiableToolList() {
		LlmInferenceService.SystemPromptRequest request = new LlmInferenceService.SystemPromptRequest(Collections.emptyList(), "<tool_call>", null);

		try {
			request.tools.add(new LlmInferenceService.ToolDefinition("x", "y", null));
			fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// a backend must not add tools the consumer will not accept calls for
		}
	}

	@Test
	public void toolCallRequestCoalescesNullArgsToAnEmptyMap() {
		LlmInferenceService.ToolCallRequest request = new LlmInferenceService.ToolCallRequest("call-1", "build", null);

		assertTrue(request.args.isEmpty());
	}

	@Test
	public void toolCallRequestCopiesTheArgsSpine() {
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("path", "app/src/Main.kt");

		LlmInferenceService.ToolCallRequest request = new LlmInferenceService.ToolCallRequest("call-1", "read_file", args);
		args.clear();

		assertEquals(1, request.args.size());
		assertEquals("app/src/Main.kt", request.args.get("path"));
	}

	@Test
	public void toolCallRequestPublishesUnmodifiableArgs() {
		LlmInferenceService.ToolCallRequest request = new LlmInferenceService.ToolCallRequest("call-1", "build", Collections.emptyMap());

		try {
			request.args.put("path", "app/src/Main.kt");
			fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// the consumer must run the call the model made, not one it rewrote
		}
	}

	@Test
	public void toolCallRequestRejectsAMissingCallId() {
		try {
			new LlmInferenceService.ToolCallRequest(null, "build", null);
			fail("expected NullPointerException");
		} catch (NullPointerException expected) {
			// without a call id the consumer cannot correlate the result it sends back
		}
	}

	@Test
	public void toolDefinitionCoalescesNullSchemaToAnEmptyMap() {
		LlmInferenceService.ToolDefinition definition = new LlmInferenceService.ToolDefinition("build", "Builds the project", null);

		assertTrue(definition.parametersSchema.isEmpty());
	}

	@Test
	public void toolDefinitionCopiesTheSchemaSpine() {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");

		LlmInferenceService.ToolDefinition definition = new LlmInferenceService.ToolDefinition("build", "Builds the project", schema);
		schema.clear();

		assertEquals(1, definition.parametersSchema.size());
		assertEquals("object", definition.parametersSchema.get("type"));
	}

	@Test
	public void toolDefinitionPublishesAnUnmodifiableSchema() {
		LlmInferenceService.ToolDefinition definition = new LlmInferenceService.ToolDefinition("build", "Builds the project", Collections.emptyMap());

		try {
			definition.parametersSchema.put("type", "object");
			fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// the consumer parses replies against the schema it offered, so it must not move
		}
	}

	@Test
	public void toolDefinitionRejectsAMissingName() {
		try {
			new LlmInferenceService.ToolDefinition(null, "Builds the project", null);
			fail("expected NullPointerException");
		} catch (NullPointerException expected) {
			// the name is how the model addresses the tool
		}
	}

	@Test
	public void toolResultCarriesBothCorrelators() {
		LlmInferenceService.ChatMessage result = LlmInferenceService.ChatMessage.toolResult("call-1", "read_file", "file contents");

		assertEquals(LlmInferenceService.ChatMessage.Role.TOOL, result.role);
		assertEquals("file contents", result.content);
		assertEquals("call-1", result.toolCallId);
		assertEquals("read_file", result.toolName);
	}

	@Test
	public void toolResultRejectsAMissingCallId() {
		try {
			LlmInferenceService.ChatMessage.toolResult(null, "read_file", "file contents");
			fail("expected NullPointerException");
		} catch (NullPointerException expected) {
			// without a call id the result cannot be matched to the call it answers
		}
	}
}

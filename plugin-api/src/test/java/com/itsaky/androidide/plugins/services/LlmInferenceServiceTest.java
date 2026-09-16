package com.itsaky.androidide.plugins.services;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Covers the validating, coalescing and copying branches of the value types plugins construct, and the contracts a plugin implements rather than calls. Every consumer of this jar is an out-of-tree plugin, so a constructor that accepts a bad state here, or a capability interface that drifts, surfaces as a runtime failure nothing in this repo compiles against.
 */
public class LlmInferenceServiceTest {

	/**
	 * Mirrors {@link HashingEmbeddingBackend}'s vector so a result can be traced back to the input that produced it.
	 */
	private static float[] expectedVector(String text, int dimensions) {
		float[] vector = new float[dimensions];
		for (int i = 0; i < dimensions; i++) {
			vector[i] = (text.hashCode() >> i) & 1;
		}
		return vector;
	}

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
	public void embeddingBackendDeclaresItsCapabilityByTypeRatherThanByAFlag() {
		LlmInferenceService.LlmBackend plain = new PlainBackend();
		LlmInferenceService.LlmBackend embedding = new HashingEmbeddingBackend(4, "text-embedding-3-small");

		assertFalse(plain instanceof LlmInferenceService.EmbeddingBackend);
		assertTrue(embedding instanceof LlmInferenceService.EmbeddingBackend);
	}

	@Test
	public void embeddingBackendFailsTheWholeBatchRatherThanPartOfIt() throws Exception {
		LlmInferenceService.EmbeddingBackend backend = new RefusingEmbeddingBackend();

		CompletableFuture<List<float[]>> future = backend.embed(Arrays.asList("first", "second"));

		try {
			future.get(5, TimeUnit.SECONDS);
			fail("expected ExecutionException");
		} catch (ExecutionException expected) {
			// nothing is handed back, so a caller cannot store half a batch and mistake it for a whole one
			assertTrue(expected.getCause() instanceof IOException);
		}
	}

	@Test
	public void embeddingBackendIgnoresAMutationOfTheCallersListAfterTheCall() throws Exception {
		LlmInferenceService.EmbeddingBackend backend = new HashingEmbeddingBackend(4, "text-embedding-3-small");
		List<String> texts = new ArrayList<>(Arrays.asList("alpha", "beta"));

		CompletableFuture<List<float[]>> future = backend.embed(texts);
		texts.clear();

		List<float[]> vectors = future.get(5, TimeUnit.SECONDS);
		assertEquals(2, vectors.size());
		assertArrayEquals(expectedVector("alpha", 4), vectors.get(0), 0.0f);
		assertArrayEquals(expectedVector("beta", 4), vectors.get(1), 0.0f);
	}

	@Test
	public void embeddingBackendKeepsConcurrentBatchesAligned() throws Exception {
		LlmInferenceService.EmbeddingBackend backend = new HashingEmbeddingBackend(4, "text-embedding-3-small");
		int batches = 32;
		ExecutorService callers = Executors.newFixedThreadPool(4);

		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<List<float[]>>> submitted = new ArrayList<>(batches);
			for (int i = 0; i < batches; i++) {
				String text = "chunk-" + i;
				submitted.add(callers.submit(() -> {
					start.await();
					return backend.embed(Collections.singletonList(text)).get(5, TimeUnit.SECONDS);
				}));
			}
			start.countDown();

			for (int i = 0; i < batches; i++) {
				List<float[]> vectors = submitted.get(i).get(10, TimeUnit.SECONDS);
				assertEquals(1, vectors.size());
				assertArrayEquals(expectedVector("chunk-" + i, 4), vectors.get(0), 0.0f);
			}
		} finally {
			callers.shutdownNow();
		}
	}

	@Test
	public void embeddingBackendNamesTheModelApartFromTheBackend() {
		LlmInferenceService.EmbeddingBackend backend = new HashingEmbeddingBackend(1536, "text-embedding-3-small");

		// two models of equal width are mutually incomparable, so the id is the part that detects a swap
		assertEquals("hashing", backend.getId());
		assertEquals("text-embedding-3-small", backend.getEmbeddingModelId());
		assertEquals(1536, backend.getEmbeddingDimensions());
	}

	@Test
	public void embeddingBackendRejectsAnEmptyBatchBeforeStartingWork() {
		LlmInferenceService.EmbeddingBackend backend = new HashingEmbeddingBackend(4, "text-embedding-3-small");

		try {
			backend.embed(Collections.emptyList());
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("texts"));
		}
	}

	@Test
	public void embeddingBackendRejectsANullTextBeforeStartingWork() {
		LlmInferenceService.EmbeddingBackend backend = new HashingEmbeddingBackend(4, "text-embedding-3-small");

		try {
			backend.embed(Arrays.asList("alpha", null));
			fail("expected NullPointerException");
		} catch (NullPointerException expected) {
			// thrown from the call, not the future, so a caller guards one path rather than two
		}
	}

	@Test
	public void embeddingBackendReturnsOneVectorPerInputInInputOrder() throws Exception {
		LlmInferenceService.EmbeddingBackend backend = new HashingEmbeddingBackend(4, "text-embedding-3-small");
		List<String> texts = Arrays.asList("alpha", "beta", "gamma");

		List<float[]> vectors = backend.embed(texts).get(5, TimeUnit.SECONDS);

		assertEquals(texts.size(), vectors.size());
		for (int i = 0; i < texts.size(); i++) {
			assertEquals(backend.getEmbeddingDimensions(), vectors.get(i).length);
			assertArrayEquals(expectedVector(texts.get(i), 4), vectors.get(i), 0.0f);
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
	public void toolCallRequestKeepsWhatTheModelAskedFor() {
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("path", "app/src/Main.kt");

		LlmInferenceService.ToolCallRequest request = new LlmInferenceService.ToolCallRequest("call-1", "read_file", args);

		assertEquals("call-1", request.callId);
		assertEquals("read_file", request.name);
		assertEquals("app/src/Main.kt", request.args.get("path"));
	}

	@Test
	public void toolDefinitionAcceptsNoParameters() {
		LlmInferenceService.ToolDefinition definition = new LlmInferenceService.ToolDefinition("build", "Builds the project", null);

		assertEquals("build", definition.name);
		assertEquals("Builds the project", definition.description);
		assertNull(definition.parametersSchema);
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

	/**
	 * A minimal conforming backend: stateless, so concurrent batches cannot interleave; validating before it starts any work; and copying the caller's list so a mutation after the call cannot reach the batch in flight.
	 */
	private static final class HashingEmbeddingBackend extends StubEmbeddingBackend {

		HashingEmbeddingBackend(int dimensions, String modelId) {
			super("hashing", dimensions, modelId);
		}

		@Override
		public CompletableFuture<List<float[]>> embed(List<String> texts) {
			List<String> batch = new ArrayList<>(Objects.requireNonNull(texts, "texts"));
			if (batch.isEmpty()) {
				throw new IllegalArgumentException("texts must not be empty");
			}
			for (String text : batch) {
				Objects.requireNonNull(text, "texts must not contain a null element");
			}
			int dimensions = getEmbeddingDimensions();
			return CompletableFuture.supplyAsync(() -> {
				List<float[]> vectors = new ArrayList<>(batch.size());
				for (String text : batch) {
					vectors.add(expectedVector(text, dimensions));
				}
				return Collections.unmodifiableList(vectors);
			});
		}
	}

	/**
	 * An {@link LlmInferenceService.LlmBackend} with no embedding capability, so the {@code instanceof} discrimination is exercised against a backend that really lacks it rather than against a mock.
	 */
	private static final class PlainBackend implements LlmInferenceService.LlmBackend {

		@Override
		public CompletableFuture<LlmInferenceService.LlmResponse> generate(String prompt, LlmInferenceService.LlmConfig config) {
			return CompletableFuture.completedFuture(LlmInferenceService.LlmResponse.success("ok", 1, 1L));
		}

		@Override
		public void generateStreaming(String prompt, LlmInferenceService.LlmConfig config, LlmInferenceService.StreamCallback callback) {
			callback.onComplete(LlmInferenceService.LlmResponse.success("ok", 1, 1L));
		}

		@Override
		public CompletableFuture<LlmInferenceService.LlmResponse> generateWithHistory(List<LlmInferenceService.ChatMessage> history, String prompt, LlmInferenceService.LlmConfig config) {
			return generate(prompt, config);
		}

		@Override
		public String getId() {
			return "plain";
		}

		@Override
		public String getName() {
			return "Plain";
		}

		@Override
		public boolean isAvailable() {
			return true;
		}
	}

	/**
	 * Reports a transport failure the documented way: the future completes exceptionally and yields no vector at all, not a short or padded list.
	 */
	private static final class RefusingEmbeddingBackend extends StubEmbeddingBackend {

		RefusingEmbeddingBackend() {
			super("refusing", 4, "text-embedding-3-small");
		}

		@Override
		public CompletableFuture<List<float[]>> embed(List<String> texts) {
			CompletableFuture<List<float[]>> future = new CompletableFuture<>();
			future.completeExceptionally(new IOException("embedding endpoint refused"));
			return future;
		}
	}

	/**
	 * The {@link LlmInferenceService.LlmBackend} half of an embedding backend, which none of these tests exercise. Kept apart from the embedding behaviour so each double below says only what it is for.
	 */
	private abstract static class StubEmbeddingBackend implements LlmInferenceService.EmbeddingBackend {

		private final int dimensions;
		private final String id;
		private final String modelId;

		StubEmbeddingBackend(String id, int dimensions, String modelId) {
			this.id = id;
			this.dimensions = dimensions;
			this.modelId = modelId;
		}

		@Override
		public CompletableFuture<LlmInferenceService.LlmResponse> generate(String prompt, LlmInferenceService.LlmConfig config) {
			return CompletableFuture.completedFuture(LlmInferenceService.LlmResponse.failure("not a generating backend"));
		}

		@Override
		public void generateStreaming(String prompt, LlmInferenceService.LlmConfig config, LlmInferenceService.StreamCallback callback) {
			callback.onError("not a generating backend");
		}

		@Override
		public CompletableFuture<LlmInferenceService.LlmResponse> generateWithHistory(List<LlmInferenceService.ChatMessage> history, String prompt, LlmInferenceService.LlmConfig config) {
			return generate(prompt, config);
		}

		@Override
		public int getEmbeddingDimensions() {
			return dimensions;
		}

		@Override
		public String getEmbeddingModelId() {
			return modelId;
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public String getName() {
			return id;
		}

		@Override
		public boolean isAvailable() {
			return true;
		}
	}
}

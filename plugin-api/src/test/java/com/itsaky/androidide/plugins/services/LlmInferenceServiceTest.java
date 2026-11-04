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
import java.util.concurrent.ExecutionException;
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
			// low 24 bits so the int survives the float conversion exactly and inputs stay distinct
			vector[i] = Objects.hash(text, i) & 0xFFFFFF;
		}
		return vector;
	}

	@Test
	public void callbackImplementingBothStreamsHearsAKindedErrorOnce() {
		// Compiles only while the two callbacks share one default for the kinded onError.
		BothCallbacks callback = new BothCallbacks();

		callback.onError("too long", LlmInferenceService.FailureKind.CONTEXT_TOO_LONG);

		assertEquals(Collections.singletonList("too long"), callback.errors);
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
	public void contextWindowIsDeclaredByTypeRatherThanByAFlag() {
		LlmInferenceService.LlmBackend plain = new PlainBackend();
		LlmInferenceService.LlmBackend windowed = new WindowedBackend(4096);

		assertFalse(plain instanceof LlmInferenceService.ContextWindowReportingBackend);
		assertEquals(4096, ((LlmInferenceService.ContextWindowReportingBackend) windowed).getContextWindowTokens());
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
	public void failureBuiltWithAKindKeepsItAndWhatWasGenerated() {
		LlmInferenceService.LlmResponse response = new LlmInferenceService.LlmResponse(false, "partial", "too long", 12, 30L,
				LlmInferenceService.FailureKind.CONTEXT_TOO_LONG);

		assertEquals(LlmInferenceService.FailureKind.CONTEXT_TOO_LONG, response.failureKind);
		assertEquals("partial", response.text);
		assertEquals(12, response.tokensGenerated);
	}

	@Test
	public void failureCarriesTheKindItWasGiven() {
		LlmInferenceService.LlmResponse response = LlmInferenceService.LlmResponse.failure("too long", LlmInferenceService.FailureKind.CONTEXT_TOO_LONG);

		assertEquals("too long", response.error);
		assertEquals(LlmInferenceService.FailureKind.CONTEXT_TOO_LONG, response.failureKind);
	}

	@Test
	public void failureRejectsANullKind() {
		try {
			LlmInferenceService.LlmResponse.failure("boom", null);
			fail("expected NullPointerException");
		} catch (NullPointerException expected) {
			// a consumer switches on the kind; null would fall through every branch
		}
	}

	@Test
	public void failureWithoutAKindIsOther() {
		LlmInferenceService.LlmResponse response = LlmInferenceService.LlmResponse.failure("boom");

		assertFalse(response.success);
		assertEquals(LlmInferenceService.FailureKind.OTHER, response.failureKind);
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
	public void responseBuiltWithoutAKindFailsAsOther() {
		LlmInferenceService.LlmResponse response = new LlmInferenceService.LlmResponse(false, null, "boom", 0, 0L);

		assertEquals(LlmInferenceService.FailureKind.OTHER, response.failureKind);
		assertEquals(LlmInferenceService.FailureKind.OTHER,
				new LlmInferenceService.LlmResponse(false, null, "boom", 0, 0L, null).failureKind);
	}

	@Test
	public void serviceBuiltBeforeListenersAcceptsThemAndNeverCalls() {
		LlmInferenceService service = new ListenerlessService();
		LlmInferenceService.BackendChangeListener listener = backendId -> {
			throw new AssertionError("a service without listener support must not call one");
		};

		service.addBackendChangeListener(listener);
		service.registerBackend(new PlainBackend());
		service.notifyBackendChanged("plain");
		service.unregisterBackend("plain");
		service.removeBackendChangeListener(listener);
	}

	@Test
	public void streamCallbackBuiltBeforeTheKindStillHearsAKindedError() {
		List<String> errors = new ArrayList<>();
		LlmInferenceService.StreamCallback callback = new LlmInferenceService.StreamCallback() {
			@Override
			public void onComplete(LlmInferenceService.LlmResponse response) {}

			@Override
			public void onError(String error) {
				errors.add(error);
			}

			@Override
			public void onToken(String token) {}
		};

		callback.onError("too long", LlmInferenceService.FailureKind.CONTEXT_TOO_LONG);

		assertEquals(Collections.singletonList("too long"), errors);
	}

	@Test
	public void successBuiltWithAKindHasNone() {
		assertNull(new LlmInferenceService.LlmResponse(true, "ok", null, 1, 1L,
				LlmInferenceService.FailureKind.CONTEXT_TOO_LONG).failureKind);
	}

	@Test
	public void successHasNoFailureKind() {
		assertNull(LlmInferenceService.LlmResponse.success("ok", 1, 1L).failureKind);
		assertNull(new LlmInferenceService.LlmResponse(true, "ok", null, 1, 1L).failureKind);
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

	@Test
	public void toolStreamCallbackOverridingTheKindedErrorReceivesOnlyThatCall() {
		List<String> calls = new ArrayList<>();
		LlmInferenceService.ToolStreamCallback callback = new LlmInferenceService.ToolStreamCallback() {
			@Override
			public void onComplete(LlmInferenceService.LlmResponse response) {}

			@Override
			public void onError(String error) {
				calls.add("plain:" + error);
			}

			@Override
			public void onError(String error, LlmInferenceService.FailureKind kind) {
				calls.add(kind + ":" + error);
			}

			@Override
			public void onToken(String token) {}

			@Override
			public void onToolCall(LlmInferenceService.ToolCallRequest request) {}
		};

		callback.onError("too long", LlmInferenceService.FailureKind.CONTEXT_TOO_LONG);

		assertEquals(Collections.singletonList("CONTEXT_TOO_LONG:too long"), calls);
	}

	/**
	 * An adapter serving both streaming paths, as a consumer that handles plain and tool-calling generations alike would write.
	 */
	private static final class BothCallbacks implements LlmInferenceService.StreamCallback, LlmInferenceService.ToolStreamCallback {
		final List<String> errors = new ArrayList<>();

		@Override
		public void onComplete(LlmInferenceService.LlmResponse response) {}

		@Override
		public void onError(String error) {
			errors.add(error);
		}

		@Override
		public void onToken(String token) {}

		@Override
		public void onToolCall(LlmInferenceService.ToolCallRequest request) {}
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
			List<String> batch = LlmInferenceService.EmbeddingBackend.requireValidBatch(texts);
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
	 * A service as ai-core built it before backend-change listeners existed: it implements only what was abstract then.
	 */
	private static final class ListenerlessService implements LlmInferenceService {

		@Override
		public void cancelGeneration() {}

		@Override
		public CompletableFuture<LlmInferenceService.LlmResponse> generateCompletion(String prompt, LlmInferenceService.LlmConfig config) {
			return CompletableFuture.completedFuture(LlmInferenceService.LlmResponse.failure("unused"));
		}

		@Override
		public void generateStreaming(String prompt, LlmInferenceService.LlmConfig config, LlmInferenceService.StreamCallback callback) {}

		@Override
		public void generateStreamingWithTools(String prompt, List<LlmInferenceService.ChatMessage> history, LlmInferenceService.LlmConfig config, List<LlmInferenceService.ToolDefinition> tools, LlmInferenceService.ToolStreamCallback callback) {}

		@Override
		public CompletableFuture<LlmInferenceService.LlmResponse> generateWithHistory(List<LlmInferenceService.ChatMessage> history, String prompt, LlmInferenceService.LlmConfig config) {
			return generateCompletion(prompt, config);
		}

		@Override
		public List<LlmInferenceService.LlmBackend> getAvailableBackends() {
			return Collections.emptyList();
		}

		@Override
		public LlmInferenceService.LlmBackend getBackend(String backendId) {
			return null;
		}

		@Override
		public CompletableFuture<float[]> getEmbeddings(String text, String backendId) {
			return CompletableFuture.completedFuture(new float[0]);
		}

		@Override
		public boolean isBackendAvailable(String backendId) {
			return false;
		}

		@Override
		public void registerBackend(LlmInferenceService.LlmBackend backend) {}

		@Override
		public void unregisterBackend(String backendId) {}
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
			LlmInferenceService.EmbeddingBackend.requireValidBatch(texts);
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

	/**
	 * Reports a fixed context window, the way a backend that loaded or was told its model's size would.
	 */
	private static final class WindowedBackend implements LlmInferenceService.ContextWindowReportingBackend {

		private final int windowTokens;

		WindowedBackend(int windowTokens) {
			this.windowTokens = windowTokens;
		}

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
		public int getContextWindowTokens() {
			return windowTokens;
		}

		@Override
		public String getId() {
			return "windowed";
		}

		@Override
		public String getName() {
			return "Windowed";
		}

		@Override
		public boolean isAvailable() {
			return true;
		}
	}
}

package com.itsaky.androidide.plugins.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

/**
 * Pins the {@code default} methods of the contributed-tool contract. Every implementor is an out-of-tree plugin, so a default that changes here changes behaviour in plugins nothing in this repo compiles against -- {@link ToolSourceRegistry.ToolSpec#requiresApproval} most of all, since silently flipping it to false would run third-party tools without asking the user.
 */
public class ToolSourceRegistryTest {

	@Test
	public void toolInvocationHasNoProjectRootUntilOneIsGiven() {
		ToolSourceRegistry.ToolInvocation invocation = new MinimalInvocation();

		assertNull(invocation.getProjectRoot());
	}

	@Test
	public void toolOutcomeCarriesNoErrorMessageUntilOneIsGiven() {
		ToolSourceRegistry.ToolOutcome outcome = new MinimalOutcome();

		assertNull(outcome.getErrorMessage());
	}

	@Test
	public void toolSourceIgnoresACancelItCannotHonour() {
		ToolSourceRegistry.ToolSource source = new MinimalSource();

		source.cancel("call-1");
	}

	@Test
	public void toolSpecIsTreatedAsHavingSideEffectsUnlessASourceOptsIn() {
		ToolSourceRegistry.ToolSpec spec = new MinimalSpec();

		assertFalse(spec.isReadOnly());
	}

	@Test
	public void toolSpecRequiresApprovalUnlessASourceOptsOut() {
		ToolSourceRegistry.ToolSpec spec = new MinimalSpec();

		assertTrue(spec.requiresApproval());
	}

	@Test
	public void toolSpecTakesNoTypedArgumentsUntilASchemaIsGiven() {
		ToolSourceRegistry.ToolSpec spec = new MinimalSpec();

		assertTrue(spec.getParametersSchema().isEmpty());
	}

	/** Implements only what the contract makes abstract, so every assertion above reads a default. */
	private static final class MinimalInvocation implements ToolSourceRegistry.ToolInvocation {

		@Override
		public Map<String, Object> getArguments() {
			return Collections.emptyMap();
		}

		@Override
		public String getCallId() {
			return "call-1";
		}

		@Override
		public String getToolName() {
			return "list_files";
		}
	}

	private static final class MinimalOutcome implements ToolSourceRegistry.ToolOutcome {

		@Override
		public String getOutput() {
			return "done";
		}

		@Override
		public boolean isSuccess() {
			return true;
		}
	}

	private static final class MinimalSource implements ToolSourceRegistry.ToolSource {

		@Override
		public String getDisplayName() {
			return "Example tools";
		}

		@Override
		public String getProviderId() {
			return "com.example.tools";
		}

		@Override
		public CompletableFuture<ToolSourceRegistry.ToolOutcome> invoke(ToolSourceRegistry.ToolInvocation invocation) {
			return CompletableFuture.completedFuture(new MinimalOutcome());
		}

		@Override
		public List<ToolSourceRegistry.ToolSpec> listTools() {
			return Collections.singletonList(new MinimalSpec());
		}
	}

	private static final class MinimalSpec implements ToolSourceRegistry.ToolSpec {

		@Override
		public String getDescription() {
			return "Lists files";
		}

		@Override
		public String getName() {
			return "list_files";
		}
	}
}

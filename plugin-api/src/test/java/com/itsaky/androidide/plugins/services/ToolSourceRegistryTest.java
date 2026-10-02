package com.itsaky.androidide.plugins.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
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
	public void registryBuiltBeforeListenersAcceptsThemAndNeverCalls() {
		MinimalRegistry registry = new MinimalRegistry();
		ToolSourceRegistry.ToolSourceListener listener = providerId -> {
			throw new AssertionError("a registry without listener support must not call one");
		};

		registry.addToolSourceListener(listener);
		registry.notifyToolSourceStatusChanged("com.example.tools");
		registry.removeToolSourceListener(listener);
	}

	@Test
	public void toolGroupIsAvailableWithNoReasonUntilItSaysOtherwise() {
		ToolSourceRegistry.ToolGroup group = new MinimalGroup();

		assertEquals(CapabilityStatus.AVAILABLE, group.getStatus());
		assertNull(group.getStatusMessage());
	}

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
	public void toolSourceBuiltBeforeContract2ClaimsNeitherStatusNorGroups() {
		ToolSourceRegistry.ToolSource source = new MinimalSource();

		assertFalse(source instanceof ToolSourceRegistry.StatusReportingToolSource);
		assertFalse(source instanceof ToolSourceRegistry.GroupedToolSource);
	}

	@Test
	public void toolSourceIgnoresACancelItCannotHonour() {
		ToolSourceRegistry.ToolSource source = new MinimalSource();

		source.cancel("call-1");
	}

	@Test
	public void toolSourceListenerHearsAStatusChangeAsAChangeUnlessItAsksToTellThemApart() {
		List<String> heard = new ArrayList<>();
		ToolSourceRegistry.ToolSourceListener listener = heard::add;

		listener.onToolSourceStatusChanged("com.example.tools");

		assertEquals(Collections.singletonList("com.example.tools"), heard);
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
	private static final class MinimalGroup implements ToolSourceRegistry.ToolGroup {

		@Override
		public String getDisplayName() {
			return "Example server";
		}

		@Override
		public String getId() {
			return "example-server";
		}

		@Override
		public List<String> getToolNames() {
			return Collections.singletonList("list_files");
		}
	}

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

	/** A registry as ai-core built it before contract 2: it knows nothing of listeners or status. */
	private static final class MinimalRegistry implements ToolSourceRegistry {

		@Override
		public List<ToolSourceRegistry.ToolSource> getToolSources() {
			return Collections.emptyList();
		}

		@Override
		public void notifyToolsChanged(String providerId) {}

		@Override
		public void registerToolSource(ToolSourceRegistry.ToolSource source) {}

		@Override
		public void unregisterToolSource(ToolSourceRegistry.ToolSource source) {}
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

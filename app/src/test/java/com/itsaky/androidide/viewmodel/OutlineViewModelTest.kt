package com.itsaky.androidide.viewmodel

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.editor.language.outline.OutlineProvider
import com.itsaky.androidide.editor.language.outline.OutlineSymbol
import com.itsaky.androidide.editor.language.outline.OutlineSymbolKind
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.ui.models.OutlineUiEffect
import com.itsaky.androidide.ui.models.OutlineUiEvent
import com.itsaky.androidide.ui.models.OutlineUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutlineViewModelTest {
	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	private class FakeOutlineProvider : OutlineProvider {
		var symbols: List<OutlineSymbol> = emptyList()
		var callCount = 0
		var gate: CompletableDeferred<Unit>? = null

		override fun supports(fileExtension: String) = fileExtension == "java"

		override suspend fun outlineOf(
			fileExtension: String,
			text: CharSequence,
		): List<OutlineSymbol> {
			callCount++
			gate?.await()
			return symbols
		}
	}

	private fun symbol(
		name: String,
		start: Int = 0,
		end: Int = 100,
	) = OutlineSymbol(
		name = name,
		detail = null,
		kind = OutlineSymbolKind.CLASS,
		range = Range(Position(0, 0, start), Position(9, 0, end)),
		selectionRange =
			Range(
				Position(0, 6, start + 6),
				Position(0, 6 + name.length, start + 6 + name.length),
			),
		children = emptyList(),
	)

	private fun TestScope.viewModel(provider: FakeOutlineProvider) = OutlineViewModel(provider, UnconfinedTestDispatcher(testScheduler))

	@Test
	fun `initial state is NoFileOpen`() =
		runTest {
			val vm = viewModel(FakeOutlineProvider())
			assertThat(vm.uiState.value).isEqualTo(OutlineUiState.NoFileOpen)
		}

	@Test
	fun `unsupported extension yields Unsupported`() =
		runTest {
			val vm = viewModel(FakeOutlineProvider())
			vm.onSnapshot("build.gradle", "gradle", "task a {}", immediate = true)
			advanceUntilIdle()
			assertThat(vm.uiState.value).isEqualTo(OutlineUiState.Unsupported("build.gradle"))
		}

	@Test
	fun `immediate snapshot with symbols yields Content without debounce delay`() =
		runTest {
			val provider = FakeOutlineProvider().apply { symbols = listOf(symbol("Main")) }
			val vm = viewModel(provider)
			vm.onSnapshot("Main.java", "java", "class Main {}", immediate = true)
			advanceTimeBy(1)
			val state = vm.uiState.value
			assertThat(state).isInstanceOf(OutlineUiState.Content::class.java)
			assertThat((state as OutlineUiState.Content).symbols.map { it.name })
				.containsExactly("Main")
		}

	@Test
	fun `no symbols yields Empty`() =
		runTest {
			val vm = viewModel(FakeOutlineProvider())
			vm.onSnapshot("Main.java", "java", "", immediate = true)
			advanceUntilIdle()
			assertThat(vm.uiState.value).isEqualTo(OutlineUiState.Empty("Main.java"))
		}

	@Test
	fun `rapid edits coalesce into one computation`() =
		runTest {
			val provider = FakeOutlineProvider().apply { symbols = listOf(symbol("Main")) }
			val vm = viewModel(provider)
			vm.onSnapshot("Main.java", "java", "class Main {}", immediate = true)
			advanceUntilIdle()
			val callsAfterSeed = provider.callCount
			vm.onSnapshot("Main.java", "java", "class Main {a}", immediate = false)
			advanceTimeBy(100)
			vm.onSnapshot("Main.java", "java", "class Main {ab}", immediate = false)
			advanceTimeBy(100)
			vm.onSnapshot("Main.java", "java", "class Main {abc}", immediate = false)
			advanceUntilIdle()
			assertThat(provider.callCount).isEqualTo(callsAfterSeed + 1)
		}

	@Test
	fun `collapse survives a re-parse of the same file`() =
		runTest {
			val provider = FakeOutlineProvider().apply { symbols = listOf(symbol("Main")) }
			val vm = viewModel(provider)
			vm.onSnapshot("Main.java", "java", "v1", immediate = true)
			advanceUntilIdle()
			vm.onEvent(OutlineUiEvent.ToggleCollapsed("Main"))
			assertThat((vm.uiState.value as OutlineUiState.Content).collapsedPaths)
				.containsExactly("Main")
			vm.onSnapshot("Main.java", "java", "v2", immediate = false)
			advanceUntilIdle()
			assertThat((vm.uiState.value as OutlineUiState.Content).collapsedPaths)
				.containsExactly("Main")
		}

	@Test
	fun `switching files shows Loading while computing and resets collapse state`() =
		runTest {
			val provider = FakeOutlineProvider().apply { symbols = listOf(symbol("Main")) }
			val vm = viewModel(provider)
			vm.onSnapshot("Main.java", "java", "v1", immediate = true)
			advanceUntilIdle()
			vm.onEvent(OutlineUiEvent.ToggleCollapsed("Main"))
			provider.gate = CompletableDeferred()
			vm.onSnapshot("Other.java", "java", "v1", immediate = true)
			advanceUntilIdle()
			assertThat(vm.uiState.value).isEqualTo(OutlineUiState.Loading("Other.java"))
			provider.gate!!.complete(Unit)
			advanceUntilIdle()
			assertThat((vm.uiState.value as OutlineUiState.Content).collapsedPaths).isEmpty()
		}

	@Test
	fun `re-parse of same file keeps previous content visible while computing`() =
		runTest {
			val provider = FakeOutlineProvider().apply { symbols = listOf(symbol("Main")) }
			val vm = viewModel(provider)
			vm.onSnapshot("Main.java", "java", "v1", immediate = true)
			advanceUntilIdle()
			provider.gate = CompletableDeferred()
			vm.onSnapshot("Main.java", "java", "v2", immediate = false)
			advanceUntilIdle()
			assertThat(vm.uiState.value).isInstanceOf(OutlineUiState.Content::class.java)
			provider.gate!!.complete(Unit)
			advanceUntilIdle()
			assertThat(vm.uiState.value).isInstanceOf(OutlineUiState.Content::class.java)
		}

	@Test
	fun `symbol click emits NavigateTo the selection start`() =
		runTest {
			val target = symbol("Main")
			val vm = viewModel(FakeOutlineProvider())
			val effects = mutableListOf<OutlineUiEffect>()
			backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				vm.effects.collect { effects.add(it) }
			}
			vm.onEvent(OutlineUiEvent.SymbolClicked(target))
			advanceUntilIdle()
			assertThat(effects)
				.containsExactly(OutlineUiEffect.NavigateTo(target.selectionRange.start))
		}

	@Test
	fun `onNoEditor returns to NoFileOpen`() =
		runTest {
			val provider = FakeOutlineProvider().apply { symbols = listOf(symbol("Main")) }
			val vm = viewModel(provider)
			vm.onSnapshot("Main.java", "java", "v1", immediate = true)
			advanceUntilIdle()
			vm.onNoEditor()
			advanceUntilIdle()
			assertThat(vm.uiState.value).isEqualTo(OutlineUiState.NoFileOpen)
		}
}

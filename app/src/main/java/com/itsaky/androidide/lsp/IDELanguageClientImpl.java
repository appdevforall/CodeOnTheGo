/*
 * This file is part of AndroidIDE.
 *
 *
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package com.itsaky.androidide.lsp;

import static com.itsaky.androidide.resources.R.drawable;
import static com.itsaky.androidide.resources.R.string;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.itsaky.androidide.activities.editor.EditorHandlerActivity;
import com.itsaky.androidide.adapters.DiagnosticsAdapter;
import com.itsaky.androidide.adapters.SearchListAdapter;
import com.itsaky.androidide.editor.ui.IDEEditor;
import com.itsaky.androidide.fragments.sheets.ProgressSheet;
import com.itsaky.androidide.lsp.api.ILanguageClient;
import com.itsaky.androidide.lsp.models.CodeActionItem;
import com.itsaky.androidide.lsp.models.DiagnosticItem;
import com.itsaky.androidide.lsp.models.DiagnosticResult;
import com.itsaky.androidide.lsp.models.PerformCodeActionParams;
import com.itsaky.androidide.lsp.models.ShowDocumentParams;
import com.itsaky.androidide.lsp.models.ShowDocumentResult;
import com.itsaky.androidide.lsp.models.TextEdit;
import com.itsaky.androidide.lsp.util.DiagnosticUtil;
import com.itsaky.androidide.models.DiagnosticGroup;
import com.itsaky.androidide.models.Location;
import com.itsaky.androidide.models.Range;
import com.itsaky.androidide.models.SearchResult;
import com.itsaky.androidide.tasks.TaskExecutor;
import com.itsaky.androidide.ui.CodeEditorView;
import com.itsaky.androidide.utils.FileUtils;
import com.itsaky.androidide.utils.FlashbarActivityUtilsKt;
import com.itsaky.androidide.utils.FlashbarUtilsKt;
import com.itsaky.androidide.utils.LSPUtils;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import kotlin.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AndroidIDE specific implementation of the LanguageClient
 */
public class IDELanguageClientImpl implements ILanguageClient {

	public static final int MAX_DIAGNOSTIC_FILES = 10;
	public static final int MAX_DIAGNOSTIC_ITEMS_PER_FILE = 20;
	protected static final Logger LOG = LoggerFactory.getLogger(IDELanguageClientImpl.class);
	private static IDELanguageClientImpl mInstance;

	public static IDELanguageClientImpl getInstance() {
		if (mInstance == null) {
			throw new IllegalStateException("Client not initialized");
		}

		return mInstance;
	}

	public static IDELanguageClientImpl initialize(EditorHandlerActivity provider) {
		if (mInstance != null) {
			throw new IllegalStateException("Client is already initialized");
		}

		mInstance = new IDELanguageClientImpl(provider);

		return getInstance();
	}

	public static boolean isInitialized() {
		return mInstance != null;
	}

	public static void shutdown() {
		if (mInstance != null) {
			mInstance.activity = null;
		}
		mInstance = null;
	}

	private final Map<File, List<DiagnosticItem>> diagnostics = new HashMap<>();

	/** Identifies the most recent {@link #showLocations(List)} request; older ones must not publish. */
	private final AtomicInteger showLocationsRequest = new AtomicInteger();

	protected EditorHandlerActivity activity;

	private IDELanguageClientImpl(EditorHandlerActivity provider) {
		setActivity(provider);
	}

	@NonNull
	public Map<File, List<DiagnosticItem>> getAllDiagnostics() {
		return new HashMap<>(this.diagnostics);
	}

	@Override
	public IDEDebugClientImpl getDebugClient() {
		return IDEDebugClientImpl.getInstance();
	}

	@Nullable
	@Override
	public DiagnosticItem getDiagnosticAt(final File file, final int line, final int column) {
		return DiagnosticUtil.binarySearchDiagnostic(this.diagnostics.get(file), line, column);
	}

	@NonNull
	@Override
	public List<DiagnosticItem> getDiagnosticsInRange(final File file, final Range range) {
		return DiagnosticUtil.findDiagnosticsInRange(this.diagnostics.get(file), range);
	}

	public DiagnosticsAdapter newDiagnosticsAdapter() {
		return new DiagnosticsAdapter(mapAsGroup(this.diagnostics), activity);
	}

	@Override
	public void performCodeAction(PerformCodeActionParams params) {
		if (params == null) {
			return;
		}

		final var action = params.getAction();
		if (!canUseActivity()) {
			LOG.error("Unable to perform code action activity=null action={}", action);
			FlashbarUtilsKt.flashError(string.msg_cannot_perform_fix);
			return;
		}

		final var currentEditor = this.activity.getCurrentEditor();
		final var editor = currentEditor != null ? currentEditor.getEditor() : null;

		if (!params.getAsync()) {
			applyActionEdits(editor, action);
			if (editor != null) {
				action.getCommand();
				editor.executeCommand(action.getCommand());
			}
			return;
		}

		final ProgressSheet progress = new ProgressSheet();
		progress.setSubMessageEnabled(false);
		progress.setCancelable(false);
		progress.setMessage(this.activity.getString(string.msg_performing_actions));
		progress.show(this.activity.getSupportFragmentManager(), "quick_fix_progress");

		TaskExecutor.executeAsyncProvideError(
				() -> applyActionEdits(editor, action),
				(result, throwable) -> {
					progress.dismiss();
					if (result == null || throwable != null || !result) {
						LOG.error("Unable to perform code action result={}", result, throwable);
						FlashbarActivityUtilsKt.flashError(this.activity, string.msg_cannot_perform_fix);
					} else if (editor != null) {
						editor.executeCommand(action.getCommand());
					}
				});
	}

	@Override
	public void publishDiagnostics(DiagnosticResult result) {
		if (result == DiagnosticResult.NO_UPDATE || !canUseActivity()) {
			// No update is expected
			return;
		}

		boolean error = result == null;
		activity.handleDiagnosticsResultVisibility(error || result.getDiagnostics().isEmpty());

		if (error) {
			return;
		}

		File file = result.getFile().toFile();
		if (!file.exists() || !file.isFile()) {
			return;
		}

		final var editorView = activity.getEditorForFile(file);
		if (editorView != null) {
			final var editor = editorView.getEditor();
			if (editor != null) {
				final var container = new DiagnosticsContainer();
				try {
					container.addDiagnostics(
							result.getDiagnostics().stream()
									.map(DiagnosticItem::asDiagnosticRegion)
									.collect(Collectors.toList()));
				} catch (Throwable err) {
					LOG.error("Unable to map DiagnosticItem to DiagnosticRegion", err);
				}
				editor.setDiagnostics(container);
			}
		}

		diagnostics.put(file, result.getDiagnostics());
		activity.setDiagnosticsAdapter(newDiagnosticsAdapter());
	}

	public void setActivity(EditorHandlerActivity provider) {
		this.activity = provider;
	}

	@Override
	public ShowDocumentResult showDocument(ShowDocumentParams params) {
		boolean success = false;
		final var result = new ShowDocumentResult(false);
		if (!canUseActivity()) {
			return result;
		}

		if (params != null) {
			File file = params.getFile().toFile();
			if (file.exists() && file.isFile() && FileUtils.isUtf8(file)) {
				final var range = params.getSelection();
				var frag = activity.getEditorAtIndex(activity.getContent().tabs.getSelectedTabPosition());
				if (frag != null
						&& frag.getFile() != null
						&& frag.getEditor() != null
						&& frag.getFile().getAbsolutePath().equals(file.getAbsolutePath())) {
					if (LSPUtils.isEqual(range.getStart(), range.getEnd())) {
						frag.getEditor().setSelection(range.getStart().getLine(), range.getStart().getColumn());
					} else {
						frag.getEditor().setSelection(range);
					}
				} else {
					activity.openFileAndSelect(file, range);
				}
				success = true;
			}
		}

		result.setSuccess(success);
		return result;
	}

	/**
	 * Called by {@link IDEEditor IDEEditor} to show locations in EditorActivity
	 */
	@Override
	public void showLocations(List<Location> locations) {

		// Cannot show anything if the activity() is null
		if (!canUseActivity()) {
			return;
		}

		// Claims the panel for this request. The publish below is asynchronous, so without this a slow
		// request that started first would land last and overwrite the newer search the user is looking at.
		final int request = showLocationsRequest.incrementAndGet();

		boolean error = locations == null || locations.isEmpty();
		if (error) {
			activity.handleSearchResultVisibility(true);
			activity
					.setSearchResultAdapter(
							new SearchListAdapter(Collections.emptyMap(), this::noOp, this::noOp));
			return;
		}

		// Group by file first. Reads then cost one pass per file instead of one full read per hit, which
		// is what this used to do - and it did it on this thread. See SearchResultGrouping.
		final Map<File, List<Location>> byFile = new LinkedHashMap<>();
		for (final Location loc : locations) {
			if (loc == null) {
				continue;
			}
			byFile.computeIfAbsent(loc.getFile().toFile(), f -> new ArrayList<>()).add(loc);
		}

		// A file with an open editor is resolved here, on the UI thread: its Content is live UI state
		// that a background thread must not touch, and pulling a few lines out of it is substring work
		// with no I/O. Everything else is read off this thread below.
		final Map<File, List<SearchResult>> fromEditors = new HashMap<>();
		final Map<File, List<Location>> onDisk = new LinkedHashMap<>();
		for (final Map.Entry<File, List<Location>> entry : byFile.entrySet()) {
			final var frag = findEditorByFile(entry.getKey());
			if (frag != null && frag.getEditor() != null) {
				final List<SearchResult> rows = SearchResultGrouping.INSTANCE.resultsFor(
						entry.getKey(), entry.getValue(), frag.getEditor().getText());
				if (!rows.isEmpty()) {
					fromEditors.put(entry.getKey(), rows);
				}
			} else {
				onDisk.put(entry.getKey(), entry.getValue());
			}
		}

		if (onDisk.isEmpty()) {
			publishLocations(fromEditors);
			return;
		}

		// Some other search may publish (and bump the generation) while the read is in flight; capture it
		// here so this request does not overwrite whatever replaced it.
		final int generation = activity.getEditorViewModel().getCurrentSearchGeneration();

		TaskExecutor.executeAsyncProvideError(
				() -> SearchResultGrouping.INSTANCE.readFromDisk(onDisk),
				(result, throwable) -> {
					if (!canUseActivity()
							|| request != showLocationsRequest.get()
							|| generation != activity.getEditorViewModel().getCurrentSearchGeneration()) {
						// Superseded, or the activity went away. Leave the panel to whoever owns it now: this
						// request's results would be an answer to a question no longer on screen.
						return;
					}
					final Map<File, List<SearchResult>> merged = new HashMap<>(fromEditors);
					if (result != null) {
						merged.putAll(result);
					} else {
						LOG.error("Failed to read search result files", throwable);
					}
					publishLocations(merged);
				});
	}

	private Boolean applyActionEdits(@Nullable final IDEEditor editor, final CodeActionItem action) {
		final var changes = action.getChanges();
		if (changes.isEmpty()) {
			return Boolean.FALSE;
		}

		for (var change : changes) {
			final var path = change.getFile();
			if (path == null) {
				continue;
			}

			final File file = path.toFile();
			if (!file.exists()) {
				continue;
			}

			for (TextEdit edit : change.getEdits()) {
				final String editorFilepath = editor == null || editor.getFile() == null ? "" : editor.getFile().getAbsolutePath();
				if (file.getAbsolutePath().equals(editorFilepath)) {
					// Edit is in the same editor which requested the code action
					editInEditor(editor, edit);
				} else {
					var openedFrag = findEditorByFile(file);

					if (openedFrag != null && openedFrag.getEditor() != null) {
						// Edit is in another 'opened' file
						editInEditor(openedFrag.getEditor(), edit);
					} else {
						// Edit is in some other file which is not opened
						// open that file and perform the edit
						activity.openFileAsync(file, null, openedEditor -> {
							if (openedEditor != null && openedEditor.getEditor() != null) {
								editInEditor(openedEditor.getEditor(), edit);
							}
							return kotlin.Unit.INSTANCE;
						});
					}
				}
			}
		}

		return Boolean.TRUE;
	}

	private boolean canUseActivity() {
		return activity != null
				&& !activity.isFinishing()
				&& !activity.isDestroyed()
				&& !activity.getSupportFragmentManager().isDestroyed()
				&& !activity.getSupportFragmentManager().isStateSaved();
	}

	private void editInEditor(final IDEEditor editor, final TextEdit edit) {
		activity
				.runOnUiThread(
						() -> {
							final Range range = edit.getRange();
							final int startLine = range.getStart().getLine();
							final int startCol = range.getStart().getColumn();
							final int endLine = range.getEnd().getLine();
							final int endCol = range.getEnd().getColumn();
							if (startLine == endLine && startCol == endCol) {
								editor.getText().insert(startLine, startCol, edit.getNewText());
							} else {
								editor.getText().replace(startLine, startCol, endLine, endCol, edit.getNewText());
							}
						});
	}

	@NonNull
	private Map<File, List<DiagnosticItem>> filterRelevantDiagnostics(
			@NonNull final Map<File, List<DiagnosticItem>> map) {
		final var result = new HashMap<File, List<DiagnosticItem>>();
		final var files = map.keySet();

		// Diagnostics of files that are open must always be included
		final var relevantFiles = findOpenFiles(files, MAX_DIAGNOSTIC_FILES);

		// If we can show a few more file diagnostics...
		if (relevantFiles.size() < MAX_DIAGNOSTIC_FILES) {
			final var alphabetical = new TreeSet<>(Comparator.comparing(File::getName));
			alphabetical.addAll(files);
			for (var file : alphabetical) {
				relevantFiles.add(file);
				if (relevantFiles.size() == MAX_DIAGNOSTIC_FILES) {
					break;
				}
			}
		}

		for (var file : relevantFiles) {
			result.put(file, map.get(file));
		}
		return result;
	}

	private CodeEditorView findEditorByFile(File file) {
		return activity.getEditorForFile(file);
	}

	@NonNull
	private Set<File> findOpenFiles(final Set<File> files, final int max) {
		final var openedFiles = activity.getEditorViewModel().getOpenedFiles();
		final var result = new TreeSet<File>();
		for (int i = 0; i < openedFiles.size(); i++) {
			final var opened = openedFiles.get(i);
			if (files.contains(opened)) {
				result.add(opened);
			}
			if (result.size() == max) {
				break;
			}
		}
		return result;
	}

	private List<DiagnosticGroup> mapAsGroup(Map<File, List<DiagnosticItem>> map) {
		final var groups = new ArrayList<DiagnosticGroup>();
		var diagnosticMap = map;
		if (diagnosticMap == null || diagnosticMap.size() == 0) {
			return groups;
		}

		if (diagnosticMap.size() > 10) {
			LOG.warn("Limiting the diagnostics to 10 files");
			diagnosticMap = filterRelevantDiagnostics(map);
		}

		for (File file : diagnosticMap.keySet()) {
			var fileDiagnostics = diagnosticMap.get(file);
			if (fileDiagnostics == null || fileDiagnostics.size() == 0) {
				continue;
			}

			// Trim the diagnostics list if we have too many diagnostic items.
			// Including a lot of diagnostic items will result in UI lag when they are shown
			if (fileDiagnostics.size() > MAX_DIAGNOSTIC_ITEMS_PER_FILE) {
				LOG.warn("Limiting diagnostics to {} items for file {}",
						MAX_DIAGNOSTIC_ITEMS_PER_FILE,
						file.getName());

				fileDiagnostics = fileDiagnostics.subList(0, MAX_DIAGNOSTIC_ITEMS_PER_FILE);
			}
			DiagnosticGroup group = new DiagnosticGroup(drawable.ic_language_java, file, fileDiagnostics);
			groups.add(group);
		}
		return groups;
	}

	private Unit noOp(final Object obj) {
		return Unit.INSTANCE;
	}

	/**
	 * Shows {@code results} in the search panel.
	 *
	 * Visibility and rows are committed together: a publish that never happens - superseded, or the activity recreated mid-read - must not leave the panel open with the "no results" placeholder hidden over the previous query's rows.
	 */
	private void publishLocations(final Map<File, List<SearchResult>> results) {
		activity.handleSearchResultVisibility(results.isEmpty());
		activity.handleSearchResults(results);
	}
}

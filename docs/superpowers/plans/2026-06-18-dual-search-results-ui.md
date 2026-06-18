# Dual Search Results UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a collapsible "Vector Code Results" section alongside the existing "Keyword Results" in the Search Results tab.

**Architecture:** Refactor SearchResultFragment to use a custom layout with two collapsible sections instead of inheriting from RecyclerViewFragment. Each section has a header with expand/collapse functionality and its own RecyclerView. The existing SearchListAdapter is reused for both sections.

**Tech Stack:** Android Kotlin, ViewBinding, StateFlow, RecyclerView

## Global Constraints

- Minimum Android SDK: Maintain existing project requirements
- No new dependencies: Reuse existing SearchListAdapter and layouts
- UI consistency: Follow existing AndroidIDE design patterns
- Data separation: Keyword and vector results use separate StateFlows in EditorViewModel

---

## File Structure

**Modified Files:**
- `app/src/main/java/com/itsaky/androidide/viewmodel/EditorViewModel.kt` - Add vector search results StateFlow
- `app/src/main/java/com/itsaky/androidide/fragments/SearchResultFragment.kt` - Refactor to custom layout with two sections
- `app/src/main/res/values/strings.xml` - Add section title strings

**New Files:**
- `app/src/main/res/layout/fragment_search_results.xml` - New fragment layout with two collapsible sections
- `app/src/main/res/layout/layout_search_section_header.xml` - Reusable collapsible section header
- `app/src/main/res/drawable/ic_chevron_down.xml` - Chevron down icon for collapsed state
- `app/src/main/res/drawable/ic_chevron_up.xml` - Chevron up icon for expanded state

---

### Task 1: Add Vector Search Results StateFlow to EditorViewModel

**Files:**
- Modify: `app/src/main/java/com/itsaky/androidide/viewmodel/EditorViewModel.kt:68-74`
- Test: Manual verification via fragment observation

**Interfaces:**
- Consumes: Nothing (standalone addition)
- Produces: `vectorSearchResults: StateFlow<Map<File, List<SearchResult>>>` and `onVectorSearchResultsReady(results: Map<File, List<SearchResult>>)` method

- [ ] **Step 1: Add private MutableStateFlow for vector search results**

Add after line 68 in EditorViewModel.kt:

```kotlin
private val _vectorSearchResults = MutableStateFlow<Map<File, List<SearchResult>>>(emptyMap())
val vectorSearchResults: StateFlow<Map<File, List<SearchResult>>> = _vectorSearchResults.asStateFlow()
```

- [ ] **Step 2: Add public method to update vector search results**

Add after line 74 in EditorViewModel.kt:

```kotlin
fun onVectorSearchResultsReady(results: Map<File, List<SearchResult>>) {
    _vectorSearchResults.value = results
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileV7DebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/itsaky/androidide/viewmodel/EditorViewModel.kt
git commit -m "feat: add vector search results StateFlow to EditorViewModel

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 2: Create Chevron Icons

**Files:**
- Create: `app/src/main/res/drawable/ic_chevron_down.xml`
- Create: `app/src/main/res/drawable/ic_chevron_up.xml`

**Interfaces:**
- Consumes: Nothing
- Produces: `@drawable/ic_chevron_down` and `@drawable/ic_chevron_up` drawable resources

- [ ] **Step 1: Create chevron down icon**

Create `app/src/main/res/drawable/ic_chevron_down.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="?attr/colorOnSurface"
        android:pathData="M7,10l5,5 5,-5z"/>
</vector>
```

- [ ] **Step 2: Create chevron up icon**

Create `app/src/main/res/drawable/ic_chevron_up.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="?attr/colorOnSurface"
        android:pathData="M7,14l5,-5 5,5z"/>
</vector>
```

- [ ] **Step 3: Verify resources exist**

Run: `./gradlew :app:processV7DebugResources`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable/ic_chevron_down.xml app/src/main/res/drawable/ic_chevron_up.xml
git commit -m "feat: add chevron icons for collapsible sections

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 3: Create Section Header Layout

**Files:**
- Create: `app/src/main/res/layout/layout_search_section_header.xml`
- Modify: `app/src/main/res/values/strings.xml` (add section titles)

**Interfaces:**
- Consumes: `@drawable/ic_chevron_down`, `@drawable/ic_chevron_up`
- Produces: `layout_search_section_header` layout resource with `id/section_title` TextView and `id/chevron_icon` ImageView

- [ ] **Step 1: Add string resources**

Add to `app/src/main/res/values/strings.xml` inside `<resources>` tag:

```xml
<string name="search_section_keyword_results">Keyword Results</string>
<string name="search_section_vector_results">Vector Code Results</string>
<string name="search_section_working">Working on it</string>
```

- [ ] **Step 2: Create section header layout**

Create `app/src/main/res/layout/layout_search_section_header.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  ~  This file is part of AndroidIDE.
  ~
  ~  AndroidIDE is free software: you can redistribute it and/or modify
  ~  it under the terms of the GNU General Public License as published by
  ~  the Free Software Foundation, either version 3 of the License, or
  ~  (at your option) any later version.
  ~
  ~  AndroidIDE is distributed in the hope that it will be useful,
  ~  but WITHOUT ANY WARRANTY; without even the implied warranty of
  ~  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  ~  GNU General Public License for more details.
  ~
  ~  You should have received a copy of the GNU General Public License
  ~   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
  -->

<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="48dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingStart="16dp"
    android:paddingEnd="16dp"
    android:background="?attr/selectableItemBackground"
    android:clickable="true"
    android:focusable="true">

    <TextView
        android:id="@+id/section_title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="16sp"
        android:textStyle="bold"
        android:textColor="?attr/colorOnSurface" />

    <ImageView
        android:id="@+id/chevron_icon"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:src="@drawable/ic_chevron_up"
        android:contentDescription="@string/search_section_keyword_results" />

</LinearLayout>
```

- [ ] **Step 3: Verify resources compile**

Run: `./gradlew :app:processV7DebugResources`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/layout_search_section_header.xml app/src/main/res/values/strings.xml
git commit -m "feat: create collapsible section header layout

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 4: Create Dual Section Fragment Layout

**Files:**
- Create: `app/src/main/res/layout/fragment_search_results.xml`

**Interfaces:**
- Consumes: `@layout/layout_search_section_header`, string resources from Task 3
- Produces: `fragment_search_results` layout with `id/keyword_header`, `id/keyword_results`, `id/vector_header`, `id/vector_results`, `id/vector_placeholder`

- [ ] **Step 1: Create fragment layout with two sections**

Create `app/src/main/res/layout/fragment_search_results.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  ~  This file is part of AndroidIDE.
  ~
  ~  AndroidIDE is free software: you can redistribute it and/or modify
  ~  it under the terms of the GNU General Public License as published by
  ~  the Free Software Foundation, either version 3 of the License, or
  ~  (at your option) any later version.
  ~
  ~  AndroidIDE is distributed in the hope that it will be useful,
  ~  but WITHOUT ANY WARRANTY; without even the implied warranty of
  ~  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  ~  GNU General Public License for more details.
  ~
  ~  You should have received a copy of the GNU General Public License
  ~   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
  -->

<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <!-- Keyword Results Section -->
        <include
            android:id="@+id/keyword_header"
            layout="@layout/layout_search_section_header" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/keyword_results"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

        <!-- Vector Code Results Section -->
        <include
            android:id="@+id/vector_header"
            layout="@layout/layout_search_section_header" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/vector_results"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:visibility="gone" />

        <TextView
            android:id="@+id/vector_placeholder"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="16dp"
            android:text="@string/search_section_working"
            android:textSize="14sp"
            android:textColor="?attr/colorOnSurfaceVariant"
            android:gravity="center" />

    </LinearLayout>

</ScrollView>
```

- [ ] **Step 2: Verify layout compiles**

Run: `./gradlew :app:processV7DebugResources`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/fragment_search_results.xml
git commit -m "feat: create dual section search results layout

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 5: Refactor SearchResultFragment

**Files:**
- Modify: `app/src/main/java/com/itsaky/androidide/fragments/SearchResultFragment.kt:33-71`

**Interfaces:**
- Consumes: `EditorViewModel.searchResults`, `EditorViewModel.vectorSearchResults`, `@layout/fragment_search_results`
- Produces: Updated `SearchResultFragment` class with dual section support

- [ ] **Step 1: Change parent class and update imports**

Replace line 33 and add imports at top of SearchResultFragment.kt:

```kotlin
import android.view.LayoutInflater
import android.view.ViewGroup
import com.itsaky.androidide.databinding.FragmentSearchResultsBinding
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R

class SearchResultFragment : Fragment() {
```

Remove the generic type parameter and RecyclerViewFragment inheritance.

- [ ] **Step 2: Add ViewBinding and state variables**

Replace lines 34-40 with:

```kotlin
  override val fragmentTooltipTag: String? = TooltipTag.PROJECT_SEARCH_RESULTS

  private var _binding: FragmentSearchResultsBinding? = null
  private val binding get() = _binding!!

  private var isKeywordExpanded = true
  private var isVectorExpanded = true

  private val editorViewModel: EditorViewModel by activityViewModels()

  private val editorActivity: BaseEditorActivity?
    get() = activity as? BaseEditorActivity
```

- [ ] **Step 3: Override onCreateView**

Replace lines 41-44 with:

```kotlin
  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = FragmentSearchResultsBinding.inflate(inflater, container, false)
    return binding.root
  }
```

- [ ] **Step 4: Implement onViewCreated with dual sections**

Replace lines 46-70 with:

```kotlin
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    setupKeywordSection()
    setupVectorSection()

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          editorViewModel.searchResults.collectLatest { results ->
            updateKeywordResults(results)
          }
        }
        launch {
          editorViewModel.vectorSearchResults.collectLatest { results ->
            updateVectorResults(results)
          }
        }
      }
    }
  }
```

- [ ] **Step 5: Add setupKeywordSection method**

Add after onViewCreated:

```kotlin
  private fun setupKeywordSection() {
    binding.keywordHeader.sectionTitle.text = getString(R.string.search_section_keyword_results)
    binding.keywordHeader.chevronIcon.contentDescription = getString(R.string.search_section_keyword_results)
    binding.keywordHeader.root.setOnClickListener {
      isKeywordExpanded = !isKeywordExpanded
      updateKeywordVisibility()
    }

    binding.keywordResults.layoutManager = LinearLayoutManager(requireContext())
    updateKeywordVisibility()
  }
```

- [ ] **Step 6: Add setupVectorSection method**

Add after setupKeywordSection:

```kotlin
  private fun setupVectorSection() {
    binding.vectorHeader.sectionTitle.text = getString(R.string.search_section_vector_results)
    binding.vectorHeader.chevronIcon.contentDescription = getString(R.string.search_section_vector_results)
    binding.vectorHeader.root.setOnClickListener {
      isVectorExpanded = !isVectorExpanded
      updateVectorVisibility()
    }

    binding.vectorResults.layoutManager = LinearLayoutManager(requireContext())
    updateVectorVisibility()
  }
```

- [ ] **Step 7: Add visibility update methods**

Add after setupVectorSection:

```kotlin
  private fun updateKeywordVisibility() {
    binding.keywordResults.visibility = if (isKeywordExpanded) View.VISIBLE else View.GONE
    binding.keywordHeader.chevronIcon.setImageResource(
      if (isKeywordExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
    )
  }

  private fun updateVectorVisibility() {
    val hasResults = binding.vectorResults.adapter?.itemCount ?: 0 > 0
    binding.vectorResults.visibility = if (isVectorExpanded && hasResults) View.VISIBLE else View.GONE
    binding.vectorPlaceholder.visibility = if (isVectorExpanded && !hasResults) View.VISIBLE else View.GONE
    binding.vectorHeader.chevronIcon.setImageResource(
      if (isVectorExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
    )
  }
```

- [ ] **Step 8: Add result update methods**

Add after updateVectorVisibility:

```kotlin
  private fun updateKeywordResults(results: Map<File, List<SearchResult>>) {
    if (isAdded && _binding != null) {
      binding.keywordResults.adapter = SearchListAdapter(
        results,
        onFileClick = { file ->
          editorActivity?.doOpenFile(file, null)
          editorActivity?.hideBottomSheet()
        },
        onMatchClick = { match ->
          editorActivity?.doOpenFile(match.file, match)
          editorActivity?.hideBottomSheet()
        }
      )
      updateKeywordVisibility()
    }
  }

  private fun updateVectorResults(results: Map<File, List<SearchResult>>) {
    if (isAdded && _binding != null) {
      binding.vectorResults.adapter = SearchListAdapter(
        results,
        onFileClick = { file ->
          editorActivity?.doOpenFile(file, null)
          editorActivity?.hideBottomSheet()
        },
        onMatchClick = { match ->
          editorActivity?.doOpenFile(match.file, match)
          editorActivity?.hideBottomSheet()
        }
      )
      updateVectorVisibility()
    }
  }
```

- [ ] **Step 9: Add onDestroyView**

Add at the end of the class:

```kotlin
  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
```

- [ ] **Step 10: Verify compilation**

Run: `./gradlew :app:compileV7DebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/itsaky/androidide/fragments/SearchResultFragment.kt
git commit -m "feat: refactor SearchResultFragment for dual sections

- Change from RecyclerViewFragment to Fragment
- Add custom layout with collapsible sections
- Support keyword and vector search results
- Implement expand/collapse functionality

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 6: Build and Test on Device

**Files:**
- Test: Full app build and device deployment

**Interfaces:**
- Consumes: All previous tasks
- Produces: Working dual-section search results UI

- [ ] **Step 1: Clean and build project**

Run: `./gradlew clean :app:assembleV7Debug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install on Samsung device**

Run: `adb -s R52XA00T20H install -r app/build/outputs/apk/v7/debug/app-v7-debug.apk`
Expected: Success message

- [ ] **Step 3: Manually test search results UI**

Manual test steps:
1. Open the app on the Samsung device
2. Navigate to Search Results tab
3. Perform a search to trigger keyword results
4. Verify "Keyword Results" section header appears
5. Verify "Vector Code Results" section header appears
6. Verify "Working on it" placeholder shows in vector section
7. Tap keyword header to collapse/expand
8. Verify chevron icon rotates
9. Tap vector header to collapse/expand
10. Verify both sections work independently

- [ ] **Step 4: Take screenshot and verify UI**

Run: `adb -s R52XA00T20H shell screencap -p > /tmp/dual_search_results.png`
Verify: Both sections visible with proper styling

- [ ] **Step 5: Final commit if any fixes needed**

```bash
git add .
git commit -m "fix: address any UI issues found during testing

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Implementation Complete

All tasks completed. The Search Results tab now has two collapsible sections:
- **Keyword Results**: Shows existing keyword-based search results
- **Vector Code Results**: Shows "Working on it" placeholder, ready for future semantic search integration

The vector search results StateFlow in EditorViewModel is ready to receive data from the llama-based semantic search implementation in a future session.

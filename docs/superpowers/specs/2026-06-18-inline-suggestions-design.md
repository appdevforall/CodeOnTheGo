# Inline Suggestions UI/UX Design
**Date:** 2026-06-18
**Feature:** GitHub Copilot-style inline suggestions for Sora editor
**Target:** CodeOnTheGo Android IDE

## Overview

This design document describes the implementation of inline code suggestions for the Sora editor in CodeOnTheGo, mimicking GitHub Copilot's ghost text behavior. The feature provides AI-powered multi-line code completions that appear as grayed-out text directly in the editor, triggered automatically after typing or manually via keyboard shortcut.

## Goals

- Provide seamless inline code suggestions similar to GitHub Copilot on VS Code
- Support both automatic (after 3 characters) and manual (Ctrl+Space/toolbar button) triggers
- Display suggestions as ghost text that users can accept with Tab or dismiss with Esc
- Support multi-line completions (3-5 lines maximum)
- Coexist with existing dropdown completion window (keywords/symbols vs AI suggestions)
- Optimize for local LLM performance on 4GB devices
- Maintain clean architecture following Sora's component pattern

## User Experience

### Visual Presentation

Suggestions appear as **ghost text** directly in the editor at the cursor position:
- Semi-transparent gray color (#808080 @ 40% opacity)
- Uses editor's current font and size
- Multi-line suggestions rendered with proper indentation
- Appears inline, not in a popup or overlay

### Interaction Model

**Acceptance:**
- Press **Tab** to accept the entire suggestion
- Suggestion text is inserted at cursor position
- Cursor moves to end of inserted text

**Dismissal:**
- Press **Esc** to dismiss suggestion
- Typing incompatible characters dismisses automatically
- Moving cursor away dismisses suggestion

**Triggers:**
1. **Automatic**: After typing 3 characters, wait 300ms idle, then request suggestion
2. **Manual**: Press Ctrl+Space or tap toolbar button to request immediately

### Coexistence with Dropdown Completion

- **Dropdown completion**: Shows keywords, symbols, LSP completions (existing behavior)
- **Inline suggestions**: Shows AI-powered multi-line completions (new behavior)
- **Priority**: Dropdown takes precedence when both are available
- **Tab behavior**: Accepts dropdown selection if visible, otherwise accepts inline suggestion

## Architecture

### Approach: Sora Component Extension

The implementation follows Sora's component pattern, similar to `EditorAutoCompletion`.

### Component Structure

```
IDEEditor
  ├── EditorAutoCompletion (existing - dropdown)
  └── InlineSuggestionComponent (new - ghost text)
      ├── GhostTextRenderer (custom drawing)
      ├── SuggestionTrigger (monitors typing)
      └── SuggestionProvider (LLM integration)
```

### Core Components

#### 1. InlineSuggestionComponent

**Purpose:** Manages the lifecycle and coordination of inline suggestions.

**Responsibilities:**
- Extends `EditorBuiltinComponent` to integrate with Sora editor
- Monitors text changes via `ContentChangeEvent`
- Tracks character count and triggers debounced requests
- Manages suggestion state machine
- Handles keyboard events (Tab/Esc)
- Coordinates between renderer and provider

**State Machine:**
```
IDLE → WAITING → REQUESTING → SHOWING → ACCEPTING/DISMISSED → IDLE
```

**Key Properties:**
```kotlin
class InlineSuggestionComponent(editor: IDEEditor) : EditorBuiltinComponent {
    private var currentSuggestion: SuggestionData? = null
    private var suggestionState: SuggestionState = SuggestionState.IDLE
    private var charactersSinceLastRequest: Int = 0
    private var debounceJob: Job? = null
    private val suggestionProvider: SuggestionProvider
    private val ghostRenderer: GhostTextRenderer
    private var enabled: Boolean = true
    private var temporarilyHidden: Boolean = false
}

enum class SuggestionState {
    IDLE,           // No suggestion active
    WAITING,        // Characters typed, waiting for debounce
    REQUESTING,     // LLM request in flight
    SHOWING,        // Suggestion visible
    ACCEPTING       // Tab pressed, committing suggestion
}

data class SuggestionData(
    val text: String,              // Multi-line suggestion text
    val startPosition: Position,   // Where suggestion starts
    val cursorLine: Int,          // Line where cursor was when requested
    val cursorColumn: Int,        // Column where cursor was
    val requestTimestamp: Long    // For caching/expiry
)
```

**Event Handling:**
- `onContentChange`: Tracks typing, increments counter, schedules debounced request
- `onSelectionChange`: Dismisses suggestion if cursor moves
- `onKeyEvent`: Intercepts Tab (accept) and Esc (dismiss)
- `onManualTrigger`: Immediately requests suggestion

#### 2. GhostTextRenderer

**Purpose:** Renders suggestion text as ghost overlay on canvas.

**Rendering Strategy:**
1. Hook into Sora's `onDraw` lifecycle
2. Calculate cursor position in screen coordinates
3. Measure ghost text using editor's font/size
4. Split multi-line suggestions, render each line
5. Apply ghost styling (semi-transparent gray)
6. Clip to visible region

**Key Methods:**
```kotlin
class GhostTextRenderer(private val editor: IDEEditor) {
    private val ghostPaint: Paint = Paint().apply {
        color = Color.argb(102, 128, 128, 128)  // 40% opacity
        textSize = editor.textSizePx
        typeface = editor.typefaceText
    }

    fun drawSuggestion(canvas: Canvas, suggestion: SuggestionData)
    fun calculateGhostTextBounds(suggestion: SuggestionData): Rect
    fun isVisible(): Boolean
}
```

**Multi-line Handling:**
- Split suggestion by newlines
- Calculate vertical offset for each line
- Respect current indentation settings
- Handle line wrapping if needed

#### 3. SuggestionProvider

**Purpose:** Interfaces with local LLM backend to generate suggestions.

**Responsibilities:**
- Send code context to LLM
- Manage request queue and cancellation
- Cache recent suggestions
- Handle errors gracefully

**Request Context:**
```kotlin
data class SuggestionContext(
    val fileContent: String,      // Current file or window around cursor
    val cursorPosition: Position, // Line and column
    val language: String,         // File type (java, kt, xml)
    val previousLines: List<String>, // Context lines before cursor
    val currentLinePrefix: String    // Text before cursor on current line
)
```

**Caching Strategy:**
- Cache key: Hash of (file path, cursor position, surrounding context)
- Cache size: 20 most recent suggestions
- Cache expiry: 30 seconds or on file edit
- Prevents redundant LLM calls

**Key Methods:**
```kotlin
class SuggestionProvider(
    private val llmRepository: LocalLlmRepositoryImpl,
    private val editor: IDEEditor
) {
    private val suggestionCache: LruCache<String, SuggestionData>
    private var activeRequest: Job? = null

    suspend fun requestSuggestion(
        cursorPosition: Position,
        fileContent: String,
        language: String
    ): SuggestionData?

    fun cancelActiveRequest()
    fun clearCache()
}
```

### Integration Points

**IDEEditor:**
- Register `InlineSuggestionComponent` during editor initialization
- Provide access to component via `getComponent(InlineSuggestionComponent::class.java)`

**EditorAutoCompletion:**
- Notify inline component when dropdown appears/dismisses
- Coordinate Tab key behavior

**LocalLlmRepositoryImpl:**
- Reuse existing LLM infrastructure from smart completion work
- Call completion API with code context
- Receive multi-line suggestions

**EditorPreferences:**
- New settings section for inline suggestions
- Enable/disable toggle
- Character threshold, debounce delay, max lines
- Keyboard shortcut configuration

**Toolbar:**
- New button for manual trigger (when enabled)
- Icon: Light bulb or sparkle
- Position: Between Format and Search buttons

## Data Flow

### Auto-trigger Flow (After 3 Characters)

1. User types character → `ContentChangeEvent` fired
2. `InlineSuggestionComponent.onContentChange()` receives event
3. Increment `charactersSinceLastRequest` counter
4. If count ≥ 3:
   - Cancel previous debounce job
   - Start new debounce job (300ms delay)
5. After 300ms idle:
   - Extract cursor position and context
   - Call `SuggestionProvider.requestSuggestion()`
   - Set state to `REQUESTING`
6. LLM returns suggestion:
   - Create `SuggestionData` object
   - Set state to `SHOWING`
   - Trigger editor invalidation
7. `GhostTextRenderer.drawSuggestion()` paints on next draw cycle

**Early Exit Conditions:**
- User continues typing during debounce → cancel and restart
- Cursor moves → cancel request
- Dropdown appears → pause inline rendering

### Manual Trigger Flow (Ctrl+Space or Toolbar)

1. User presses Ctrl+Space or taps toolbar button
2. Bypass character counter
3. Immediately request suggestion
4. Show loading indicator (optional)
5. Display suggestion when ready
6. If no suggestion or error, show brief toast

### Acceptance Flow (Tab Key)

1. User presses Tab while suggestion showing
2. `InlineSuggestionComponent.onKeyEvent()` intercepts Tab
3. Set state to `ACCEPTING`
4. Extract suggestion text
5. Insert text at cursor using `editor.text.insert()`
6. Move cursor to end of inserted text
7. Clear suggestion and return to `IDLE`
8. Reset character counter
9. Editor invalidates and re-renders

**Multi-line Insertion:**
- Split suggestion by `\n`
- Insert each line with proper indentation
- Handle tabs/spaces based on editor settings
- Position cursor at end of last line

### Dismissal Flow

**Esc Key:**
1. User presses Esc while suggestion showing
2. Intercept key event
3. Clear `currentSuggestion`
4. Set state to `IDLE`
5. Invalidate editor

**Continued Typing:**
1. User types while suggestion showing
2. Check if new character compatible with suggestion
3. If incompatible → dismiss
4. If compatible → keep showing, reset counter

### Coexistence Logic

**When Dropdown Appears:**
- Pause inline suggestion rendering (keep in memory)
- Let dropdown handle completions

**When Dropdown Dismissed:**
- If no item selected → resume inline suggestion (if still valid)
- If item selected → dismiss inline suggestion, reset counter

**Tab Key Priority:**
1. If dropdown visible with selection → complete dropdown
2. If dropdown visible without selection → dismiss dropdown
3. If only inline suggestion visible → accept inline suggestion

## Error Handling & Edge Cases

### LLM Request Failures

**Timeout:**
- Set 10-second timeout for LLM requests
- Retry once automatically on failure
- After 2 failures, disable auto-trigger for 60 seconds
- Manual trigger still works during cooldown
- Log errors silently (no user disruption)

**Empty Suggestions:**
- If LLM returns empty/whitespace → silent failure
- On manual trigger, show toast: "No suggestion available"

### Edge Cases

**Rapid Typing:**
- Cancel in-flight requests on new characters
- Only keep most recent request active
- Debounce prevents spam

**Cursor Movement:**
- Moving cursor dismisses suggestion immediately
- Exception: Arrow keys to end of line keeps suggestion

**File Switching:**
- Clear all suggestions when switching files
- Cancel pending requests
- Reset character counter

**Undo/Redo:**
- Accepted suggestions part of undo history
- Undo removes entire suggestion as one unit
- Dismissed suggestions don't affect undo stack

**Selection Mode:**
- Dismiss suggestions when text selected
- Don't trigger during selection

**Read-Only Files:**
- Disable component for read-only/archive files
- Check `editor.isEditable` before showing

### Performance Safeguards

**Memory Management:**
- Limit cache to 20 suggestions
- Clear cache on low memory warning
- Release resources when editor paused

**UI Thread Protection:**
- All LLM requests on background thread
- Rendering on UI thread (lightweight)
- Use `postInvalidate()` when off UI thread

**Battery Considerations:**
- Respect Android Doze mode
- Don't trigger when screen off
- Optional: Disable auto-trigger when battery < 20%

## Configuration & Settings

### New Preferences

Add to `EditorPreferences`:

```kotlin
object InlineSuggestionPreferences {
    const val ENABLED = "inline_suggestion_enabled"
    const val AUTO_TRIGGER = "inline_suggestion_auto_trigger"
    const val CHAR_THRESHOLD = "inline_suggestion_char_threshold"
    const val DEBOUNCE_MS = "inline_suggestion_debounce_ms"
    const val MAX_LINES = "inline_suggestion_max_lines"
    const val MANUAL_SHORTCUT = "inline_suggestion_manual_shortcut"
    const val SHOW_TOOLBAR_BUTTON = "inline_suggestion_toolbar_button"
}
```

**Defaults:**
- Enabled: `true`
- Auto-trigger: `true`
- Character threshold: `3`
- Debounce delay: `300ms`
- Max lines: `5`
- Manual shortcut: `"Ctrl+Space"`
- Show toolbar button: `true`

### Settings UI

Add new section in Editor settings:
- Enable Inline Suggestions (toggle)
- Auto-trigger after typing (toggle)
- Character threshold (slider: 2-5)
- Delay before suggestion (slider: 100-1000ms)
- Maximum suggestion lines (slider: 1-10)
- Keyboard shortcut (dropdown)
- Show toolbar button (toggle)

### Toolbar Button

- **Icon**: Light bulb or sparkle
- **Position**: Between Format and Search
- **Tooltip**: "Get AI suggestion (Ctrl+Space)"
- **State**: Disabled for read-only files
- **Feedback**: Brief highlight when suggestion appears

## File Structure

New files to create:

```
editor/src/main/java/com/itsaky/androidide/editor/ui/
  ├── InlineSuggestionComponent.kt       (main component)
  ├── GhostTextRenderer.kt               (rendering logic)
  └── SuggestionProvider.kt              (LLM interface)

editor/src/main/res/drawable/
  └── ic_inline_suggestion.xml           (toolbar icon)

preferences/src/main/java/com/itsaky/androidide/preferences/internal/
  └── InlineSuggestionPreferences.kt     (settings)

app/src/main/res/xml/
  └── preferences_inline_suggestions.xml (settings UI)
```

Modified files:

```
editor/src/main/java/com/itsaky/androidide/editor/ui/IDEEditor.kt
  - Register InlineSuggestionComponent in initialization
  - Expose component via getComponent()

app/src/main/java/com/itsaky/androidide/activities/editor/BaseEditorActivity.kt
  - Add toolbar button for manual trigger

editor/src/main/java/com/itsaky/androidide/editor/ui/EditorAutoCompletion.kt
  - Notify inline component of dropdown state changes

preferences/src/main/java/com/itsaky/androidide/preferences/internal/EditorPreferences.kt
  - Reference inline suggestion preferences
```

## Testing Strategy

### Manual Testing

1. **Basic Flow**: Type 3 characters → see ghost text → press Tab → text inserted
2. **Manual Trigger**: Press Ctrl+Space → ghost text appears
3. **Dismissal**: Press Esc → ghost text disappears
4. **Multi-line**: Verify multi-line suggestions render correctly with indentation
5. **Coexistence**: Verify dropdown and inline suggestions don't conflict
6. **Performance**: Test on 4GB device, verify no lag or jank

### Edge Case Testing

1. Rapid typing during suggestion request
2. Cursor movement while suggestion showing
3. File switching with active suggestion
4. Undo/redo after accepting suggestion
5. Suggestions in read-only files (should be disabled)

### Performance Testing

1. Memory usage with cache full
2. Rendering performance with long multi-line suggestions
3. LLM request cancellation during rapid typing
4. Battery impact with continuous usage

## Implementation Phases

### Phase 1: Core Infrastructure (Foundation)
- Create `InlineSuggestionComponent` skeleton
- Implement state machine
- Add to IDEEditor initialization
- Add preferences structure

### Phase 2: Rendering (Visual)
- Implement `GhostTextRenderer`
- Hook into Sora's draw lifecycle
- Support single-line ghost text first
- Add multi-line rendering

### Phase 3: Provider Integration (Backend)
- Implement `SuggestionProvider`
- Integrate with `LocalLlmRepositoryImpl`
- Add request context building
- Implement caching

### Phase 4: Triggers & Interaction (UX)
- Implement auto-trigger (3 char + debounce)
- Add Tab/Esc key handling
- Add manual trigger (Ctrl+Space)
- Add toolbar button

### Phase 5: Coexistence & Polish (Integration)
- Coordinate with `EditorAutoCompletion`
- Handle all edge cases
- Add error handling
- Performance optimization

### Phase 6: Settings & Documentation (Finalization)
- Add settings UI
- Implement all preference handling
- Write user documentation
- Test on target devices

## Success Criteria

- ✅ Ghost text appears after typing 3 characters with 300ms idle
- ✅ Manual trigger (Ctrl+Space) works immediately
- ✅ Tab accepts suggestion, Esc dismisses
- ✅ Multi-line suggestions render correctly (3-5 lines)
- ✅ Coexists with dropdown completion without conflicts
- ✅ No UI lag or jank on 4GB devices
- ✅ Settings panel allows full configuration
- ✅ Works reliably with local LLM backend

## Future Enhancements (Out of Scope)

- Partial acceptance (accept word-by-word)
- Multiple suggestion cycling (Alt+] / Alt+[)
- Context-aware trigger patterns
- Suggestion ranking/filtering
- Telemetry and analytics
- Cloud LLM fallback option

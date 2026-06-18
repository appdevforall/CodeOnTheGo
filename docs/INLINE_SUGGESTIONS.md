# Inline Suggestions

GitHub Copilot-style inline code suggestions for CodeOnTheGo.

## Features

- **Auto-trigger**: Suggestions appear after typing 3 characters (configurable)
- **Manual trigger**: Press Ctrl+Space or toolbar button for immediate suggestions
- **Ghost text**: Semi-transparent gray text appears inline at cursor
- **Multi-line**: Supports up to 5 lines of suggestions
- **Accept/Dismiss**: Press Tab to accept, Esc to dismiss

## Usage

### Auto-trigger Mode

1. Start typing in the editor
2. After 3 characters, wait 300ms
3. Ghost text suggestion appears at cursor
4. Press Tab to accept, or keep typing to dismiss

### Manual Trigger

- Press **Ctrl+Space** for immediate suggestion
- Or click the **light bulb button** in the toolbar

### Settings

Configure inline suggestions in:
**Settings > Editor > Inline Suggestions**

- Enable/disable feature
- Toggle auto-trigger
- Adjust character threshold (2-5)
- Change debounce delay (100-1000ms)
- Set max suggestion lines (1-10)
- Show/hide toolbar button

## Technical Details

- Powered by local LLM (no internet required)
- Coexists with dropdown completion (keywords/symbols)
- Optimized for 4GB devices
- Caches recent suggestions (30 second expiry)

## Keyboard Shortcuts

- **Ctrl+Space**: Manual trigger
- **Tab**: Accept suggestion
- **Esc**: Dismiss suggestion

## Troubleshooting

**No suggestions appearing:**
- Check that inline suggestions are enabled in settings
- Verify LLM model is loaded
- Ensure file is editable (not read-only)

**Suggestions slow:**
- Increase debounce delay in settings
- Reduce max lines to 1-3 for faster processing

**Conflicts with dropdown:**
- Dropdown completion takes priority
- Inline suggestions pause when dropdown visible

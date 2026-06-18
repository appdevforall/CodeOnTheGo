# LLM ADB Command Interface

This document describes how to trigger LLM features via ADB broadcast commands.

## Overview

The `LlmCommandReceiver` provides a broadcast-based interface for triggering LLM operations from the command line using `adb`. This is useful for:
- Testing and debugging
- Automation and scripting
- CI/CD integration
- Remote control

## Basic Usage

All commands use the same broadcast action with different parameters:

```bash
adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "<action_type>" \
  [additional parameters...]
```

## Available Commands

### 1. Vector Search Test

Search for semantically similar code files using embeddings.

**Command:**
```bash
adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "vector_search" \
  --es query "main" \
  --ei max_files 10
```

**Parameters:**
- `action` (required): `"vector_search"`
- `query` (required): Search query string (e.g., "authentication", "database", "main")
- `max_files` (optional): Maximum number of files to index (default: 10)

**Output:**
Results are logged to logcat with tag `LlmCommandReceiver`. Use:
```bash
adb logcat | grep -E "LlmCommandReceiver|VectorSearch"
```

**Example Output:**
```
I LlmCommandReceiver: VectorSearch: query='main', maxFiles=10
I LlmCommandReceiver: VectorSearch: executing command...
I VectorSearchTest: Found 10 source files to index
I VectorSearchTest: Query embedding generated: dimension=384
I VectorSearchTest: Indexed [1/10]: MainActivity.kt (similarity: 0.823)
...
I LlmCommandReceiver: VectorSearch: SUCCESS
I LlmCommandReceiver: ============================================================
I LlmCommandReceiver: === Vector Search Test Results ===
I LlmCommandReceiver: Query: main
I LlmCommandReceiver: Indexed 10 files
I LlmCommandReceiver:
I LlmCommandReceiver: Top matches:
I LlmCommandReceiver: 1. MainActivity.kt
I LlmCommandReceiver:    Similarity: 0.8234
I LlmCommandReceiver:    Path: app/src/main/java/com/.../MainActivity.kt
I LlmCommandReceiver: ============================================================
```

### 2. Feature B (Placeholder)

Reserved for future local LLM feature.

**Command:**
```bash
adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "feature_b" \
  --es input "some_value"
```

**Status:** Not yet implemented. Will log a warning when called.

### 3. Feature C (Placeholder)

Reserved for future feature with multiple parameters.

**Command:**
```bash
adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "feature_c" \
  --es param1 "value1" \
  --es param2 "value2"
```

**Status:** Not yet implemented. Will log a warning when called.

## Parameter Types

ADB supports different parameter types in broadcast intents:

- `--es <key> <value>`: String parameter
- `--ei <key> <value>`: Integer parameter
- `--ez <key> <value>`: Boolean parameter (true/false)
- `--ef <key> <value>`: Float parameter
- `--el <key> <value>`: Long parameter

## Troubleshooting

### Command Not Working

1. **Check app is running:**
   ```bash
   adb shell dumpsys activity | grep com.itsaky.androidide
   ```

2. **Check receiver is registered:**
   ```bash
   adb shell dumpsys package com.itsaky.androidide | grep LlmCommandReceiver
   ```

3. **View all logs:**
   ```bash
   adb logcat -s LlmCommandReceiver:* VectorSearchTest:*
   ```

### Model Not Loaded

If you see "LlmInferenceEngine not available", make sure:
1. An encoder model (like all-miniLM) is loaded in the app
2. The model is compatible with embeddings extraction

### No Results

If vector search returns no results:
1. Check that project files exist in the workspace
2. Verify the model supports embeddings (encoder models only)
3. Increase `max_files` parameter to scan more files

## Examples

### Quick Test
```bash
# Simple vector search for "main"
adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "vector_search" \
  --es query "main"
```

### Search for Authentication Code
```bash
adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "vector_search" \
  --es query "authentication and login logic" \
  --ei max_files 20
```

### Search for Database Operations
```bash
adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "vector_search" \
  --es query "database queries and SQL" \
  --ei max_files 15
```

### Monitor Results in Real-time
```bash
# In one terminal, watch logs:
adb logcat -s LlmCommandReceiver:* VectorSearchTest:*

# In another terminal, trigger search:
adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "vector_search" \
  --es query "your search query"
```

## Adding New Features

To add a new LLM feature accessible via ADB:

1. Define a new action constant in `LlmCommandReceiver.kt`:
   ```kotlin
   private const val ACTION_TYPE_YOUR_FEATURE = "your_feature"
   ```

2. Add parameter constants:
   ```kotlin
   private const val EXTRA_YOUR_PARAM = "your_param"
   ```

3. Add handler method:
   ```kotlin
   private fun handleYourFeature(context: Context, intent: Intent) {
       val param = intent.getStringExtra(EXTRA_YOUR_PARAM)
       // Implementation
   }
   ```

4. Add case to `onReceive()`:
   ```kotlin
   ACTION_TYPE_YOUR_FEATURE -> handleYourFeature(context, intent)
   ```

5. Update this documentation with usage examples.

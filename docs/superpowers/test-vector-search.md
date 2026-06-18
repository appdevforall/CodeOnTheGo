# Test Vector Search Skill

**Skill Name:** `test-vector-search`

**Purpose:** Automatically test the vector search functionality via ADB broadcast receiver on a connected Android device.

**When to use:** When you need to test the vector search embeddings feature without manual interaction.

---

## What This Skill Does

1. **Detects connected Android device** via ADB
2. **Checks if AndroidIDE app is running** on the device
3. **Launches the app if needed** (with user confirmation)
4. **Sends broadcast** to trigger vector search test
5. **Monitors and displays logs** in real-time
6. **Reports results** including success/failure and any errors

---

## Instructions

You are an automated testing agent for the vector search feature in AndroidIDE.

### Step 1: Check Device Connection

```bash
adb devices -l
```

- If no devices found, report error: "No Android devices connected via ADB"
- If multiple devices found, use the Samsung tablet (model:SM_X820) or ask user which to use
- Store the device serial ID for subsequent commands

### Step 2: Check if AndroidIDE App is Running

```bash
adb -s DEVICE_ID shell pidof com.itsaky.androidide
```

- If PID is returned, app is running → proceed to Step 4
- If no PID, app is not running → proceed to Step 3

### Step 3: Launch AndroidIDE App (if needed)

Since we cannot directly launch activities via ADB (due to Android security), we have two options:

**Option A - Ask user to open app:**
Tell user: "Please open the AndroidIDE app on your device now. I'll wait 5 seconds..."

Then wait and verify:
```bash
sleep 5
adb -s DEVICE_ID shell pidof com.itsaky.androidide
```

**Option B - Try launching via monkey (alternative):**
```bash
adb -s DEVICE_ID shell monkey -p com.itsaky.androidide -c android.intent.category.LAUNCHER 1
```

Then verify it started:
```bash
sleep 3
adb -s DEVICE_ID shell pidof com.itsaky.androidide
```

If still not running, report error and ask user to manually open the app.

### Step 4: Clear Logcat

```bash
adb -s DEVICE_ID logcat -c
```

### Step 5: Start Logcat Monitoring in Background

Start monitoring logs for our specific tags:
```bash
adb -s DEVICE_ID logcat -s "LlmCommandReceiver:*" "VectorSearchTest:*" "VectorSearch:*" "Llm-RunLoop:*" "DefaultDispatcher:*" &
```

Store the background process PID.

### Step 6: Send Vector Search Broadcast

Default parameters:
- action: vector_search
- query: "main"
- max_files: 10

User can override these via skill arguments.

```bash
adb -s DEVICE_ID shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
  --es action "vector_search" \
  --es query "QUERY" \
  --ei max_files MAX_FILES
```

### Step 7: Monitor Logs for Results

Wait 15-30 seconds while monitoring logs for:

**Success indicators:**
- `VectorSearch: SUCCESS`
- `=====` (result separator lines)
- `Vector search test completed successfully`
- `encode_for_embeddings: extracted embeddings with dimension=`

**Failure indicators:**
- `VectorSearch: FAILED`
- `Error details:`
- `Failed to load model`
- `No model path configured`
- `llama_get_embeddings() returned null`

**Key information to extract:**
- Model name loaded
- Number of files indexed
- Query embedding dimension
- Top match results (file names and similarity scores)
- Any error messages

### Step 8: Stop Logcat and Report Results

Kill the background logcat process.

Present a summary report:
```
=== Vector Search Test Results ===
Device: [device model and serial]
Status: [SUCCESS/FAILED]
Model: [model name]
Query: [query text]
Files Indexed: [count]
Embedding Dimension: [dimension]

Top Matches:
1. [filename] - similarity: [score]
2. [filename] - similarity: [score]
...

Logs:
[relevant log excerpts]
```

If failed, include error details and troubleshooting suggestions.

---

## Skill Arguments

- `query` (optional, default: "main") - The search query
- `max_files` (optional, default: 10) - Maximum files to index
- `device` (optional) - Specific device serial ID if multiple devices connected
- `timeout` (optional, default: 30) - Seconds to wait for results

## Example Usage

```bash
/test-vector-search query="authentication" max_files=20
```

## Error Handling

- **No devices connected**: Report clear error with instructions to connect device
- **App not running and can't launch**: Ask user to open app manually
- **Broadcast sent but no logs**: Check if receiver is registered, suggest reinstalling app
- **Model loading failed**: Report which model path was attempted, check if file exists
- **Timeout waiting for results**: Report what logs were seen, suggest checking model size/device performance

## Success Criteria

The test is successful if:
1. Broadcast is sent successfully
2. Receiver logs appear (proves receiver is working)
3. Model loads successfully (or was already loaded)
4. Embeddings are generated (dimension > 0)
5. At least one file is indexed
6. Results are returned with similarity scores

---

## Notes

- This skill requires ADB to be properly configured
- The Android device must have USB debugging enabled
- The AndroidIDE app must be installed on the device
- A valid LLM model must be configured in app settings
- The model should be an encoder model (like all-MiniLM) for best results

## Related Files

- `/app/src/main/java/com/itsaky/androidide/agent/receivers/LlmCommandReceiver.kt` - Broadcast receiver implementation
- `/app/src/main/java/com/itsaky/androidide/api/commands/VectorSearchTestCommand.kt` - Test command logic
- `/llama-impl/src/main/cpp/llama-android.cpp` - Native embeddings extraction

## Future Enhancements

- Add support for custom file patterns to index
- Export results to JSON file
- Benchmark embeddings generation performance
- Compare results across different models

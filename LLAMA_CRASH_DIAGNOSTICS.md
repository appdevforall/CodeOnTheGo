# LLM Crash Diagnostics & Protection

## Overview
This document describes the comprehensive protections added to prevent crashes when using incompatible or problematic LLM models, and how to diagnose issues if crashes still occur.

---

## Protection Layers Added

### 1. **File Format Validation (Pre-Load)**
**Location:** `app/.../LlmInferenceEngine.kt:validateModelFormat()`

Checks file extension before loading:
- ❌ `.onnx` - ONNX models not supported
- ❌ `.pt`, `.pth`, `.bin` - PyTorch models not supported
- ❌ `.safetensors` - HuggingFace format not supported
- ❌ `.pb`, `.tflite` - TensorFlow models not supported
- ❌ `.ggml` - Legacy format (deprecated)
- ✅ `.gguf` - Supported format

**Logs to check:**
```
WARN: Model file 'xxx' doesn't have .gguf extension. May fail to load.
WARN: Model 'xxx' appears to be an embedding model based on filename.
```

---

### 2. **Binary Format Validation (Load Time)**
**Location:** `llama-impl/.../llama-android.cpp:is_valid_gguf_file()`

Validates GGUF magic number (0x46554747) at file start.

**Logs to check:**
```
ERROR: Invalid GGUF file format: <path>
ERROR: load_model() failed - model loading returned null
```

**Error message if fails:**
```
Invalid model file format. This app only supports GGUF format models.
Please ensure you have selected a valid .gguf model file.
```

---

### 3. **Model Architecture Validation (Context Creation)**
**Location:** `llama-impl/.../llama-android.cpp:Java_android_llama_cpp_LLamaAndroid_new_1context()`

Validates model metadata before creating context:
- Vocabulary size > 0
- Training context size > 0
- Clamps context to model's max training context

**Logs to check:**
```
INFO: Model info: vocab=32000, ctx_train=4096
INFO: Creating context with n_ctx=4096, n_threads=4, n_threads_batch=4
INFO: Context created successfully
```

**Error messages if fails:**
```
Model has invalid vocabulary. The model file may be corrupted or incompatible.
Model has invalid training context. The model file may be corrupted.
Failed to create model context. This may indicate:
1. Insufficient memory
2. Incompatible model architecture
3. Corrupted model file
```

---

### 4. **Embedding Model Detection (Load Time)**
**Location:** `llama-impl/.../LLamaAndroid.kt:load()`

Checks if model is embedding-only model via `pooling_type`:
- `0` = NONE (generative) ✅
- `1` = MEAN (embedding) ❌
- `2` = CLS (embedding) ❌
- `3` = LAST (embedding) ❌
- `4` = RANK (reranking) ❌

**Logs to check:**
```
INFO: Model description: Llama 3.2 1B Instruct Q4_K_M
INFO: Model pooling type: 0 (0=generative, 1=mean, 2=cls, 3=last, 4=rank)
```

**Error message if embedding model:**
```
This model is an embedding model (pooling_type=1) and cannot be used
for text generation. Please select a chat/instruct model instead.
Embedding models are designed for semantic search and similarity tasks,
not conversational AI.
```

---

### 5. **Pre-Inference Validation (Runtime)**
**Location:** `llama-impl/.../LLamaAndroid.kt:send()`

Before each inference:
- Re-validates pooling type (defensive check)
- Validates message length vs context size
- Checks token count + max output <= context size

**Logs to check:**
```
DEBUG: Starting inference - formatChat=false, clearCache=true, nlen=256
DEBUG: Model pooling_type: 0
DEBUG: Context size: 4096, message tokens: 45, max output: 256
DEBUG: Clearing KV cache
DEBUG: Calling completion_init
```

**Error messages if validation fails:**
```
Cannot perform text generation with an embedding model (pooling_type=1).
Message is too long for the model's context window.
  Message requires 2000 tokens plus 256 for output, but context is only 2048 tokens.
```

---

### 6. **Tokenization Validation (Native)**
**Location:** `llama-impl/.../llama-android.cpp:completion_init()`

Validates tokenization before inference:
- Text is not empty
- Tokenization produces at least 1 token
- Context size is valid
- Required KV cache size <= context size

**Logs to check:**
```
INFO: Tokenizing input (parse_special=0)...
INFO: Tokenized 45 tokens
INFO: n_len = 256, n_ctx = 4096, n_tokens = 45, n_kv_req = 301
INFO: Processing batch with 45 tokens
INFO: Calling llama_decode for initial prompt processing...
INFO: Initial decode completed successfully
```

**Error messages if validation fails:**
```
Invalid text input
Failed to tokenize input text. The text may be empty or invalid.
Model context size is invalid. Model may be corrupted.
Prompt is too long for the model's context size. Try a shorter message.
```

---

### 7. **Decode Operation Protection (Native)**
**Location:** `llama-impl/.../llama-android.cpp:completion_init()` and `completion_loop()`

Wraps `llama_decode()` with error handling:

**Logs to check:**
```
INFO: Calling llama_decode for initial prompt processing...
INFO: Initial decode completed successfully
```

**Error codes and meanings:**
- `-1` = Memory/architecture issue
- `-2` = Invalid state
- Other = Unknown error

**Error message if decode fails:**
```
Model decode failed (error -1). This may indicate:
1. Insufficient memory for model operations
2. Incompatible model architecture
3. Corrupted model file
Try: Restart app, use smaller model, or free memory
```

---

### 8. **Generation Loop Protection (Kotlin)**
**Location:** `llama-impl/.../LLamaAndroid.kt:send()`

Wraps entire generation loop with try-catch:

**Logs to check:**
```
DEBUG: Starting generation loop
DEBUG: Generation completed after 42 iterations
```

**Error message if generation fails:**
```
Text generation failed: <error details>.
This may indicate insufficient memory, model incompatibility,
or corrupted model file.
```

---

## Diagnosing a Crash

If the app still crashes with SIGABRT, follow these steps:

### Step 1: Check Application Logs
Filter logcat for relevant information:

```bash
# Check for model loading logs
adb logcat | grep -i "llama"

# Check for error messages
adb logcat | grep -E "(ERROR|FAILED|SIGABRT)"

# Check specific tags
adb logcat LLamaAndroid:* llama.cpp:* *:E
```

### Step 2: Identify Crash Point

Look for the **last successful log** before crash:

#### Crash during load:
```
INFO: Loading model from <path>
[CRASH - no "Loaded model" message]
```
→ **Issue:** Model file is corrupted or incompatible architecture

#### Crash during context creation:
```
INFO: Model description: ...
INFO: Model pooling type: 0
[CRASH - no "Context created" message]
```
→ **Issue:** Insufficient memory or incompatible context parameters

#### Crash during tokenization:
```
DEBUG: Calling completion_init
INFO: Tokenizing input...
[CRASH - no "Tokenized X tokens" message]
```
→ **Issue:** Text encoding issue or model vocabulary problem

#### Crash during initial decode:
```
INFO: Calling llama_decode for initial prompt processing...
[CRASH - no "Initial decode completed" message]
```
→ **Issue:** Model architecture incompatibility or memory issue

#### Crash during generation:
```
DEBUG: Starting generation loop
[CRASH after some iterations]
```
→ **Issue:** Memory exhaustion, invalid token, or model state corruption

---

### Step 3: Collect Diagnostic Information

If crash occurs, collect:

1. **Model Information:**
   - Model name/source
   - File size
   - Quantization level (Q4_K_M, Q5_K_S, etc.)

2. **Device Information:**
   - Available RAM
   - Free storage
   - CPU architecture (ARM, x86)

3. **Application Logs:**
   ```bash
   adb logcat -d > logcat_crash.txt
   ```

4. **Last Logs Before Crash:**
   - Model description
   - Pooling type
   - Context size
   - Token counts

---

## Common Issues and Solutions

### Issue: "Model description: xxx embedding"
**Cause:** Embedding model detected
**Solution:** Use a chat/instruct model instead

### Issue: "pooling_type: 1" (or 2, 3, 4)
**Cause:** Model is designed for embeddings, not text generation
**Solution:** Download a generative model (Llama, Qwen, Gemma chat variants)

### Issue: Crash with "vocab=0" or "ctx_train=0"
**Cause:** Corrupted model file
**Solution:** Re-download model or try different quantization

### Issue: "llama_decode() failed with error -1"
**Cause:** Insufficient memory for model
**Solution:**
- Close other apps
- Use smaller model (1B instead of 3B)
- Use higher quantization (Q4 instead of Q5/Q6)

### Issue: Crash during generation after N tokens
**Cause:** Memory exhaustion during inference
**Solution:**
- Reduce max tokens setting
- Clear app cache
- Restart device
- Use smaller context size

---

## Recommended Models

These models are **confirmed compatible** with the app:

### Small (< 1GB, for 4GB RAM devices):
- `Llama-3.2-1B-Instruct-Q4_K_M.gguf`
- `Qwen2.5-0.5B-Instruct-Q4_K_M.gguf`
- `gemma-2-2b-it-Q4_K_M.gguf`

### Medium (1-2GB, for 6GB+ RAM devices):
- `Llama-3.2-3B-Instruct-Q4_K_M.gguf`
- `Qwen2.5-1.5B-Instruct-Q4_K_M.gguf`

### Large (2-4GB, for 8GB+ RAM devices):
- `Llama-3.1-8B-Instruct-Q4_K_M.gguf`
- `Qwen2.5-3B-Instruct-Q4_K_M.gguf`

**Avoid these model types:**
- ❌ Embedding models (all-MiniLM, e5-*, all-mpnet)
- ❌ Vision models (LLaVA, CogVLM)
- ❌ Audio models (Whisper)
- ❌ Non-GGUF formats

---

## Testing the Fix

To verify the protections work:

1. **Test embedding model rejection:**
   - Try to load `all-MiniLM-L6-v2.gguf` (if you can find it)
   - Should see clear error about embedding models

2. **Test invalid format rejection:**
   - Try to load `.onnx` or `.pt` file
   - Should see format validation error

3. **Test context size validation:**
   - Send very long message (> 2000 words)
   - Should see context size error, not crash

4. **Test memory limits:**
   - Load largest model your device can handle
   - Send messages and verify graceful errors if OOM

---

## Emergency Recovery

If app crashes on every start due to auto-loading bad model:

1. Clear app data:
   ```bash
   adb shell pm clear com.itsaky.androidide
   ```

2. Or manually remove cached model:
   ```bash
   adb shell rm /data/data/com.itsaky.androidide/cache/local_model.gguf
   ```

3. Or use app settings to change AI backend to Gemini API temporarily

---

## Report Issues

If crash persists after these protections, report with:
1. Model name and source URL
2. Full logcat output (`adb logcat -d > crash.txt`)
3. Device specs (RAM, CPU)
4. Steps to reproduce

This will help identify any remaining edge cases not covered by current protections.

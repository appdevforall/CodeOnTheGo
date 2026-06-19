# Testing Embedding Model Protection

## What's Been Done

✅ **Native C++ protections** added to detect embedding models
✅ **Kotlin validation test** runs during model load
✅ **New llama-impl AAR** built with protections
✅ **Version updated** to 6 (forces re-extraction)
✅ **App built** with new AAR included

---

## Install and Test

### 1. Install the APK
```bash
adb install -r app/build/outputs/apk/v8/debug/app-v8-debug.apk
```

### 2. Clear App Data (CRITICAL!)
This forces the app to extract the new AAR v6:
```bash
adb shell pm clear com.itsaky.androidide
```

### 3. Start Logging
```bash
adb logcat -c
adb logcat | grep -E "(llama|LLama|pooling|validation|embed)" | tee test-log.txt
```

### 4. Try to Load all-mini Model
- Open the app
- Go to AI settings
- Select the all-mini model
- Try to load it

---

## Expected Results

### If Native Protection Works (Best Case):
During context creation:
```
INFO: Context pooling_type: 1 (0=none/generative, 1=mean/embed, ...)
ERROR: REJECTED: Model is configured for embeddings (pooling_type=1)
ERROR: This model is an embedding model and cannot be used for text generation.
       Embedding models use 'encode' operations, not 'decode'.
       Please select a chat/instruct model.
```

### If Kotlin Validation Catches It (Fallback):
During model load validation:
```
INFO: Loaded model <path>
INFO: Validating model can perform text generation (dry run test)...
ERROR: Model validation failed - this is likely an embedding model
ERROR: Model validation failed: Cannot perform text generation.

       This typically indicates:
       • An embedding model (e.g., all-MiniLM, e5, bge, mpnet)
       • Incompatible model architecture
       • Corrupted model file

       Please use a chat/instruct model instead:
       • Llama-3.2-1B-Instruct-Q4_K_M.gguf
       • Qwen2.5-0.5B-Instruct-Q4_K_M.gguf
       • gemma-2-2b-it-Q4_K_M.gguf
```

### What Should NOT Happen:
❌ **NO CRASH** - No SIGABRT
❌ **NO "decode: cannot decode batches"** repeated messages
❌ **NO app freeze or ANR**

---

## Verification Checklist

- [ ] App installed successfully
- [ ] App data cleared
- [ ] New AAR extracted to `/data/data/com.itsaky.androidide/app_llama_unzipped/v6/`
- [ ] Embedding model rejected with clear error message
- [ ] Error message is user-friendly and actionable
- [ ] No crash occurred
- [ ] Chat model still works normally (test after)

---

## Check Extracted Libraries

Verify new libraries are loaded:
```bash
# Should show v6 directory (not v5)
adb shell ls -l /data/data/com.itsaky.androidide/app_llama_unzipped/

# Check shared libraries in v6
adb shell ls -lh /data/data/com.itsaky.androidide/app_llama_unzipped/v6/jni/arm64-v8a/

# Should see recent timestamps on these files:
# - libggml-base.so
# - libggml-cpu.so
# - libggml.so
# - libllama.so
```

---

## If It Still Crashes

Collect these logs:
```bash
# Full logcat
adb logcat -d > crash-full.txt

# Just llama-related
adb logcat -d | grep -E "(llama|LLama|pooling|embed|SIGABRT)" > crash-llama.txt
```

Look for:
1. Which version directory was used (v5 or v6)?
2. Was the validation test run?
3. What was the last log before crash?
4. Was pooling_type checked?

---

## Success Criteria

✅ Model rejected during load (not during inference)
✅ Clear, actionable error message shown
✅ No SIGABRT crash
✅ App remains stable
✅ Can load a proper chat model after

---

## Next: Test With Chat Model

After confirming embedding model is rejected, test with a working model:

1. Download a chat model:
   - `Llama-3.2-1B-Instruct-Q4_K_M.gguf`
   - Or `Qwen2.5-0.5B-Instruct-Q4_K_M.gguf`

2. Load it and verify:
   ```
   INFO: Model pooling type: 0 (0=generative, ...)
   INFO: Validating model can perform text generation...
   INFO: Model validation passed
   INFO: Loaded model successfully
   ```

3. Send a test message and verify it generates text normally

---

## Summary

This test verifies that:
1. The new native libraries were properly built and packaged
2. The AAR was correctly extracted (v6)
3. Native-level pooling type checks work
4. Kotlin-level validation tests work
5. User sees helpful error messages
6. App doesn't crash

**All protections are now in place at both native and Kotlin layers!**

# Building and Deploying New Llama AAR with Protections

## Overview
This guide walks through building new llama.cpp libraries with our C++ protections and packaging them into AAR files for the app.

---

## Step 1: Build the Native Libraries

### For ARM64 (v8a) - Most common
```bash
cd /Users/john/Documents/cogo/CodeOnTheGo

# Clean previous builds
./gradlew :llama-impl:clean

# Build ARM64 libraries (Release)
./gradlew :llama-impl:externalNativeBuildV8Release

# Verify built libraries
find llama-impl/.cxx -name "*.so" -path "*arm64-v8a*" -path "*Release*"
```

Expected output:
```
llama-impl/.cxx/Release/.../arm64-v8a/bin/libggml-base.so
llama-impl/.cxx/Release/.../arm64-v8a/bin/libggml-cpu.so
llama-impl/.cxx/Release/.../arm64-v8a/bin/libllama.so
llama-impl/.cxx/Release/.../arm64-v8a/bin/libllama-android.so
```

### For ARM32 (v7a) - Optional
```bash
# Build ARM32 libraries (Release)
./gradlew :llama-impl:externalNativeBuildV7Release

# Verify built libraries
find llama-impl/.cxx -name "*.so" -path "*armeabi-v7a*" -path "*Release*"
```

---

## Step 2: Package Libraries into AAR

### Create Staging Directory
```bash
mkdir -p aar-staging/v8
mkdir -p aar-staging/v7
```

### Package ARM64 AAR
```bash
cd aar-staging/v8

# Find the exact build path (it has a hash)
BUILD_PATH=$(find ../../llama-impl/.cxx/Release -type d -name "arm64-v8a" -path "*/Release/*" | head -1)

echo "Using build path: $BUILD_PATH"

# Copy native libraries
mkdir -p jni/arm64-v8a
cp $BUILD_PATH/bin/*.so jni/arm64-v8a/

# Copy classes.dex from existing AAR (we're only updating native libs)
unzip ../../app/src/main/assets/dynamic_libs/llama-v8.aar classes.dex -d .

# Verify files
ls -lh jni/arm64-v8a/
ls -lh classes.dex

# Create AAR
zip -r ../llama-v8-new.aar *

cd ../..
```

### Package ARM32 AAR (Optional)
```bash
cd aar-staging/v7

BUILD_PATH=$(find ../../llama-impl/.cxx/Release -type d -name "armeabi-v7a" -path "*/Release/*" | head -1)

mkdir -p jni/armeabi-v7a
cp $BUILD_PATH/bin/*.so jni/armeabi-v7a/

unzip ../../app/src/main/assets/dynamic_libs/llama-v7.aar classes.dex -d .

zip -r ../llama-v7-new.aar *

cd ../..
```

---

## Step 3: Compress AARs (Optional but Recommended)

```bash
# Install brotli if needed
# macOS: brew install brotli
# Linux: apt-get install brotli

# Compress ARM64 AAR
brotli -q 11 aar-staging/llama-v8-new.aar -o aar-staging/llama-v8-new.aar.br

# Compress ARM32 AAR (if built)
brotli -q 11 aar-staging/llama-v7-new.aar -o aar-staging/llama-v7-new.aar.br

# Check sizes
ls -lh aar-staging/*.aar*
```

---

## Step 4: Update App Assets

### Backup Old AARs
```bash
mkdir -p aar-backups
cp app/src/main/assets/dynamic_libs/llama-v8.aar* aar-backups/ 2>/dev/null || true
cp app/src/main/assets/dynamic_libs/llama-v7.aar* aar-backups/ 2>/dev/null || true
```

### Install New AARs
```bash
# Remove old files
rm -f app/src/main/assets/dynamic_libs/llama-v8.aar*
rm -f app/src/main/assets/dynamic_libs/llama-v7.aar*

# Copy new AARs (compressed or uncompressed)
if [ -f aar-staging/llama-v8-new.aar.br ]; then
    cp aar-staging/llama-v8-new.aar.br app/src/main/assets/dynamic_libs/llama-v8.aar.br
else
    cp aar-staging/llama-v8-new.aar app/src/main/assets/dynamic_libs/llama-v8.aar
fi

# Optional: ARM32
if [ -f aar-staging/llama-v7-new.aar.br ]; then
    cp aar-staging/llama-v7-new.aar.br app/src/main/assets/dynamic_libs/llama-v7.aar.br
elif [ -f aar-staging/llama-v7-new.aar ]; then
    cp aar-staging/llama-v7-new.aar app/src/main/assets/dynamic_libs/llama-v7.aar
fi

# Verify
ls -lh app/src/main/assets/dynamic_libs/
```

---

## Step 5: Update Version Number

Edit `app/src/main/java/com/itsaky/androidide/utils/DynamicLibraryLoader.kt`:

```kotlin
// Change this line:
private const val LLAMA_LIB_VERSION = 5

// To:
private const val LLAMA_LIB_VERSION = 6
```

This forces the app to re-extract and use the new libraries.

---

## Step 6: Build and Test

### Build APK
```bash
./gradlew :app:assembleV8Debug
```

### Install on Device
```bash
adb install -r app/build/outputs/apk/v8/debug/app-v8-debug.apk
```

### Clear App Data (Important!)
```bash
# This forces re-extraction of new AAR
adb shell pm clear com.itsaky.androidide
```

### Test
1. Open app
2. Try to load the all-mini model
3. Should now see clear error BEFORE crash:

```
Model validation failed: Cannot perform text generation.

This typically indicates:
• An embedding model (e.g., all-MiniLM, e5, bge, mpnet)
• Incompatible model architecture
• Corrupted model file

Please use a chat/instruct model instead:
• Llama-3.2-1B-Instruct-Q4_K_M.gguf
• Qwen2.5-0.5B-Instruct-Q4_K_M.gguf
• gemma-2-2b-it-Q4_K_M.gguf
```

### Check Logs
```bash
adb logcat | grep -E "(LLamaAndroid|llama|validation|pooling)"
```

Expected logs:
```
INFO: Model description: ...
INFO: Model pooling type: 1 (0=generative, 1=mean, ...)
INFO: Validating model can perform text generation (dry run test)...
ERROR: Model validation failed - this is likely an embedding model
```

Or with new native code:
```
INFO: Context pooling_type: 1 (0=none/generative, 1=mean/embed, ...)
ERROR: REJECTED: Model is configured for embeddings (pooling_type=1)
```

---

## Troubleshooting

### Build Fails
```bash
# Check NDK is installed
ls $ANDROID_HOME/ndk/

# Clean and retry
./gradlew :llama-impl:clean
rm -rf llama-impl/.cxx
./gradlew :llama-impl:externalNativeBuildV8Release --info
```

### Libraries Not Found After Build
```bash
# Find all built .so files
find llama-impl/.cxx -name "*.so" -type f

# Check the exact path structure
ls -R llama-impl/.cxx/Release/*/arm64-v8a/bin/
```

### AAR Not Extracting on Device
```bash
# Check extraction happened
adb shell ls /data/data/com.itsaky.androidide/app_llama_unzipped/

# Should show v6 directory
# If still showing v5, version number wasn't updated
```

### Still Crashes
```bash
# Verify new libraries are loaded
adb shell ls -l /data/data/com.itsaky.androidide/app_llama_unzipped/v6/jni/arm64-v8a/

# Check file dates - should be recent
# Compare sizes with old libraries in v5/
```

---

## Quick Script

Save this as `rebuild-llama-aar.sh`:

```bash
#!/bin/bash
set -e

echo "Building ARM64 libraries..."
./gradlew :llama-impl:externalNativeBuildV8Release

echo "Finding build output..."
BUILD_PATH=$(find llama-impl/.cxx/Release -type d -name "arm64-v8a" -path "*/Release/*" | head -1)
echo "Build path: $BUILD_PATH"

echo "Creating staging directory..."
rm -rf aar-staging
mkdir -p aar-staging/v8

echo "Packaging AAR..."
cd aar-staging/v8
mkdir -p jni/arm64-v8a
cp $BUILD_PATH/bin/*.so jni/arm64-v8a/
unzip ../../app/src/main/assets/dynamic_libs/llama-v8.aar classes.dex -d . 2>/dev/null || \
unzip ../../app/src/main/assets/dynamic_libs/llama-v8.aar.br classes.dex -d . 2>/dev/null || \
    echo "Warning: Could not extract classes.dex"
zip -r ../llama-v8-new.aar *
cd ../..

echo "Compressing..."
brotli -f -q 11 aar-staging/llama-v8-new.aar -o aar-staging/llama-v8-new.aar.br

echo "Backing up old AAR..."
mkdir -p aar-backups
cp app/src/main/assets/dynamic_libs/llama-v8.aar* aar-backups/ 2>/dev/null || true

echo "Installing new AAR..."
rm -f app/src/main/assets/dynamic_libs/llama-v8.aar*
cp aar-staging/llama-v8-new.aar.br app/src/main/assets/dynamic_libs/llama-v8.aar.br

echo "Update LLAMA_LIB_VERSION in DynamicLibraryLoader.kt to 6"
echo "Then run: ./gradlew :app:assembleV8Debug"
echo "Done!"
```

Make executable:
```bash
chmod +x rebuild-llama-aar.sh
```

Run:
```bash
./rebuild-llama-aar.sh
```

---

## Verification Checklist

- [ ] Native libraries built successfully
- [ ] AAR created and contains .so files
- [ ] AAR compressed (optional)
- [ ] Old AAR backed up
- [ ] New AAR installed in assets
- [ ] LLAMA_LIB_VERSION incremented to 6
- [ ] App builds without errors
- [ ] App data cleared before testing
- [ ] New libraries extracted to v6/ directory
- [ ] Embedding model rejected with clear error
- [ ] Chat model works normally

---

## What Changed in the C++ Code

Our new native protections add:

1. **Context Creation Validation**
   - Checks pooling_type immediately after context creation
   - Rejects embedding models before any inference

2. **Decode Pre-checks**
   - Validates pooling_type before every decode operation
   - Prevents SIGABRT by catching incompatible models early

3. **Enhanced Error Messages**
   - Clear explanations of what went wrong
   - Actionable guidance on what to do

With these changes, embedding models will be rejected at the native layer with clear errors instead of crashing.

# Building and Running Code On The Go on macOS

This guide explains how to compile Code On The Go and run it on macOS using the Android Emulator.

## Prerequisites

### 1. Java Development Kit (JDK 17)

**Current status:** You have JDK 21 installed, but this project requires **JDK 17**.

**Install JDK 17:**
```bash
# Using Homebrew
brew install openjdk@17

# Add to your PATH (add to ~/.zshrc or ~/.bash_profile)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"

# Verify installation
java -version  # Should show version 17.x.x
```

### 2. Android Studio

**Required:** Android Studio (latest stable version)

**Download:** https://developer.android.com/studio

**Why needed:**
- Provides Android SDK
- Includes Android Emulator
- Gradle integration
- IDE for development

**Installation:**
1. Download Android Studio DMG
2. Drag to Applications folder
3. Launch and complete setup wizard
4. Install Android SDK (API 33+ recommended)

### 3. Android SDK Components

**Already installed:** ✅ Android SDK at `~/Library/Android/sdk`

**Required SDK components:**
```bash
# Open Android Studio → Settings → Appearance & Behavior → System Settings → Android SDK

# Install these in SDK Platforms tab:
- Android 13.0 (Tiramisu) API Level 33 ✅ (minimum)
- Android 14.0 (UpsideDownCake) API Level 34
- Android 15.0 (VanillaIceCream) API Level 35

# Install these in SDK Tools tab:
- Android SDK Build-Tools (latest)
- Android SDK Platform-Tools ✅ (already installed)
- Android Emulator
- Android SDK Command-line Tools
- CMake (for native code)
- NDK (Side by side) - version 25.x or later
```

### 4. Android Emulator Setup

**Current status:** ⚠️ No emulator AVDs configured

**Create an emulator:**

#### Option A: Using Android Studio GUI
1. Open Android Studio
2. Tools → Device Manager
3. Click "Create Device"
4. Select device (recommended: Pixel 7 or Pixel 8)
5. Select system image: API 33+ (x86_64 architecture)
6. Name it: `CodeOnTheGo_Emulator`
7. Click Finish

#### Option B: Using Command Line
```bash
# Add emulator to PATH
export PATH="$HOME/Library/Android/sdk/emulator:$PATH"
export PATH="$HOME/Library/Android/sdk/cmdline-tools/latest/bin:$PATH"

# Download system image
sdkmanager "system-images;android-33;google_apis;x86_64"

# Create AVD
avdmanager create avd -n CodeOnTheGo_Emulator \
  -k "system-images;android-33;google_apis;x86_64" \
  -d "pixel_7"

# List AVDs to verify
emulator -list-avds
```

### 5. Git Hooks (Optional but Recommended)

```bash
# From project root
sh ./scripts/install-git-hooks.sh
```

## Building the Project

### 1. Clone and Open Project

```bash
# Navigate to project directory
cd /Users/john/Documents/cogo/CodeOnTheGo

# Ensure you're on the correct branch
git branch --show-current
```

### 2. Sync Gradle Dependencies

```bash
# Clean and sync
./gradlew clean

# This will download all dependencies
./gradlew tasks
```

### 3. Build Debug APK

```bash
# Build v8 (ARM64) debug APK
./gradlew :app:assembleV8Debug

# Output location:
# app/build/outputs/apk/v8/debug/app-v8-debug.apk
```

**Build variants:**
- `v7` - ARMv7 (32-bit, older devices)
- `v8` - ARM64 (64-bit, modern devices) ✅ Recommended

### 4. Build Options

```bash
# Build all variants
./gradlew :app:assembleDebug

# Build release (requires signing)
./gradlew :app:assembleRelease

# Build and install on connected device
./gradlew :app:installV8Debug

# Build without Llama assets (faster for testing UI changes)
INCLUDE_LLAMA_ASSETS=false ./gradlew :app:assembleV8Debug
```

## Running on Android Emulator

### Method 1: Using Gradle

```bash
# Start emulator first (in background)
emulator -avd CodeOnTheGo_Emulator &

# Wait for emulator to boot, then install
./gradlew :app:installV8Debug

# Or build and install in one command
./gradlew :app:installV8Debug --info
```

### Method 2: Using ADB

```bash
# Start emulator
emulator -avd CodeOnTheGo_Emulator &

# Install APK
adb install app/build/outputs/apk/v8/debug/app-v8-debug.apk

# Or reinstall (keeps data)
adb install -r app/build/outputs/apk/v8/debug/app-v8-debug.apk
```

### Method 3: Using Android Studio

1. Open project in Android Studio
2. Wait for Gradle sync to complete
3. Select emulator from device dropdown (top toolbar)
4. Click Run button (green play icon) or press `^R`

## Viewing the App on macOS Desktop

### Desktop View Options

#### 1. Android Emulator Window
- **Native experience**: Emulator runs in its own window
- **Controls**: Resizable, rotation, volume, home/back buttons
- **Performance**: Best performance, hardware acceleration

```bash
# Start emulator with specific resolution
emulator -avd CodeOnTheGo_Emulator -skin 1080x2400
```

#### 2. Android Studio Device Mirror
- **Path**: Tools → Device Manager → Select running device → Show device mirror
- **Features**: Embedded in IDE, side-by-side with code

#### 3. Scrcpy (Screen Mirroring)
- **Best for**: Wireless mirroring, recording, low latency
- **Install**: `brew install scrcpy`
- **Usage**:
```bash
# Install scrcpy
brew install scrcpy

# Mirror emulator screen
scrcpy
```

### Recommended Emulator Settings for Desktop Development

```bash
# Start with specific display settings
emulator -avd CodeOnTheGo_Emulator \
  -skin 1080x2400 \
  -gpu host \
  -no-snapshot-load \
  -memory 4096
```

**Emulator window controls:**
- `Cmd + M`: Show/hide extended controls
- `Cmd + R`: Rotate device
- `Cmd + Up/Down`: Volume control
- `Cmd + P`: Power button

## Troubleshooting

### Issue: "Cannot find Java 17"
```bash
# Set JAVA_HOME in ~/.zshrc
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
source ~/.zshrc
```

### Issue: "SDK location not found"
```bash
# Create local.properties in project root
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

### Issue: Emulator won't start
```bash
# Check emulator list
emulator -list-avds

# Run with verbose logging
emulator -avd CodeOnTheGo_Emulator -verbose

# Check available system images
sdkmanager --list | grep system-images
```

### Issue: Build fails with "OutOfMemoryError"
```bash
# Already configured in gradle.properties with 8GB heap
# If still failing, close other apps or increase in gradle.properties:
org.gradle.jvmargs=-Xmx10240M
```

### Issue: Emulator is slow
1. Enable hardware acceleration (should be automatic on macOS)
2. Use x86_64 system images (not ARM)
3. Allocate more RAM in AVD settings
4. Enable KVM (if available)

## Development Workflow

### Typical Development Cycle

```bash
# 1. Start emulator
emulator -avd CodeOnTheGo_Emulator &

# 2. Make code changes in your editor

# 3. Build and install
./gradlew :app:installV8Debug

# 4. Check logs
adb logcat | grep "AndroidIDE"

# 5. Take screenshot
adb exec-out screencap -p > screenshot.png
```

### Fast Development Iteration

```bash
# Use Gradle's continuous build
./gradlew :app:installV8Debug --continuous

# Or use Android Studio's "Apply Changes" (Cmd+Shift+R)
```

## Quick Reference

### Essential Commands

```bash
# Build
./gradlew :app:assembleV8Debug

# Install
./gradlew :app:installV8Debug

# Build + Install + Run
./gradlew :app:installV8Debug && adb shell am start -n com.itsaky.androidide/.MainActivity

# Clean build
./gradlew clean :app:assembleV8Debug

# List connected devices
adb devices

# View logs
adb logcat -v color

# Uninstall
adb uninstall com.itsaky.androidide
```

### Environment Setup Script

Create `~/.zshrc` additions:

```bash
# Java 17 for Code On The Go
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"

# Android SDK
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
export PATH="$ANDROID_SDK_ROOT/emulator:$PATH"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

# Gradle
export GRADLE_OPTS="-Xmx4g"

# Aliases for convenience
alias emulator-start="emulator -avd CodeOnTheGo_Emulator &"
alias cogo-build="./gradlew :app:assembleV8Debug"
alias cogo-install="./gradlew :app:installV8Debug"
alias cogo-logs="adb logcat -v color | grep AndroidIDE"
```

Then reload: `source ~/.zshrc`

## Next Steps

1. ✅ Install JDK 17
2. ✅ Install Android Studio (if not already)
3. ✅ Create Android Emulator AVD
4. ✅ Build the project: `./gradlew :app:assembleV8Debug`
5. ✅ Start emulator: `emulator -avd CodeOnTheGo_Emulator &`
6. ✅ Install app: `./gradlew :app:installV8Debug`
7. ✅ Start developing!

## Additional Resources

- [Android Studio Download](https://developer.android.com/studio)
- [Android Emulator Guide](https://developer.android.com/studio/run/emulator)
- [Gradle Build Guide](https://developer.android.com/build)
- [Contributing Guide](./CONTRIBUTING.md)
- [Project README](./README.md)

## Support

If you encounter issues:
- [Report bugs/issues](https://github.com/appdevforall/CodeOnTheGo/issues)
- [Telegram discussions](https://t.me/CodeOnTheGoDiscussions)
- [Email support](mailto:feedback@appdevforall.org)

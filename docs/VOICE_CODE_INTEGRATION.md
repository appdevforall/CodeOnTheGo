# Voice Code Integration Guide

This document explains how to integrate the speech-to-code feature into ProjectHandlerActivity.

## Quick Setup

Add one line to `ProjectHandlerActivity.onCreate()`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // ... existing onCreate code ...

    // Add voice code integration (at the end of onCreate)
    setupVoiceCode()
}
```

That's it! The extension function will:
- ✅ Check if voice code is enabled in preferences
- ✅ Check microphone permissions
- ✅ Initialize SpeechToCodeViewModel
- ✅ Wire up LLM controller
- ✅ Setup all observers (recording overlay, preview sheet, errors)

## How It Works

### 1. User Triggers Voice Code

User taps the microphone icon in the toolbar → `VoiceCodeAction.execAction()` → calls `activity.startVoiceRecording()`

### 2. Recording Flow

```
startVoiceRecording()
  ↓
viewModel.startRecording()
  ↓
AudioRecorder captures audio
  ↓
VoiceRecordingOverlay shows waveform
  ↓
User releases or tap to stop
  ↓
viewModel.stopRecordingAndProcess()
```

### 3. Processing Flow

```
stopRecordingAndProcess()
  ↓
SpeechToCodePipeline.processAudio()
  ↓
STT (Cloud or Moonshine) → Transcription
  ↓
VoiceCommandRecognizer → Intent detection
  ↓
LlamaController → Code generation
  ↓
viewModel.previewData → VoicePreviewBottomSheet
```

### 4. Code Insertion

```
User taps "Insert" on preview sheet
  ↓
viewModel.insertCode(code, editor)
  ↓
Insert with or without typing animation
  ↓
Done!
```

## Files Added

### Core Logic
- `app/src/main/java/com/itsaky/androidide/speech/`
  - `AudioRecorder.kt` - Captures 16kHz PCM audio
  - `AndroidSpeechRecognizer.kt` - Cloud STT (Google)
  - `MoonshineSTT.kt` - Offline STT (ONNX)
  - `SpeechToCodePipeline.kt` - Main pipeline (STT → Intent → LLM)
  - `VoiceCommandRecognizer.kt` - Intent detection
  - `VoicePreferences.kt` - Settings management

### UI Components
- `app/src/main/java/com/itsaky/androidide/ui/voice/`
  - `WaveformVisualizer.kt` - Audio waveform visualization
  - `VoiceRecordingOverlay.kt` - Recording UI overlay
  - `VoicePreviewBottomSheet.kt` - Code preview sheet
  - `VoiceLanguageDialog.kt` - Language selector

### ViewModel & Actions
- `app/src/main/java/com/itsaky/androidide/viewmodel/SpeechToCodeViewModel.kt`
- `app/src/main/java/com/itsaky/androidide/actions/voice/VoiceCodeAction.kt`

### Integration
- `app/src/main/java/com/itsaky/androidide/activities/editor/VoiceCodeIntegration.kt` ⭐ **Extension functions**

### Settings
- `app/src/main/java/com/itsaky/androidide/preferences/aiPrefExts.kt`
  - AI & Voice settings screen
  - STT mode toggle (Cloud/Offline)
  - Language selector
  - Preview and animation toggles

### Resources
- `resources/src/main/res/values/strings.xml` - UI strings
- `docs/SPANISH_VOICE_SUPPORT.md` - Multilingual guide
- `docs/VOICE_CODE_INTEGRATION.md` - This file

## Settings

Users can configure voice code in:

**Settings → Configure → AI & Voice → Speech to Code**

Options:
- ✅ Enable Voice Code (on/off)
- ☁️ Speech Recognition (Cloud / Offline)
- 🌐 Voice Language (en-US, es-ES, es-MX, es-AR)
- 👁️ Preview Before Insert (on/off)
- ⌨️ Typing Animation (on/off)

## Troubleshooting

### "Voice recording: Please connect ViewModel in activity"
→ Add `setupVoiceCode()` to ProjectHandlerActivity.onCreate()

### "AI model not loaded. Please check AI settings."
→ Ensure LLM is loaded in LocalLlmRepositoryImpl

### "Microphone permission required for voice code."
→ Grant RECORD_AUDIO permission

### No speech detected
→ Check internet connection (for Cloud STT)
→ Or download Moonshine models (for Offline STT)

### Code not generating
→ Check if LLM is loaded and working
→ Try simpler voice commands like "create a function"

## Example Voice Commands

### English (en-US)
- "create a function to calculate total"
- "refactor this code"
- "add a RecyclerView adapter"
- "explain what this does"

### Spanish (es-ES, es-MX, es-AR)
- "crear una función para calcular el total"
- "refactorizar este código"
- "agregar un adaptador de RecyclerView"
- "explicar qué hace esto"

## STT Modes

### Cloud (Default)
- **Pros**: High accuracy, zero setup, supports all languages
- **Cons**: Requires internet, ~500-800ms latency
- **Provider**: Android SpeechRecognizer (Google)

### Offline (Moonshine)
- **Pros**: No internet, faster (~200-400ms), privacy
- **Cons**: Requires 118MB models, English only currently
- **Models**: /sdcard/Download/models/moonshine/

## Architecture

```
┌─────────────────────┐
│ VoiceCodeAction     │ (Toolbar button)
└──────────┬──────────┘
           │ triggers
           ▼
┌─────────────────────┐
│ ProjectHandler      │
│ Activity            │
│ + setupVoiceCode()  │ ← Extension function
│ + startVoiceRecording()
└──────────┬──────────┘
           │ uses
           ▼
┌─────────────────────┐
│ SpeechToCodeViewModel│
│ - AudioRecorder     │
│ - SpeechToCodePipeline│
└──────────┬──────────┘
           │ processes
           ▼
┌─────────────────────┐
│ SpeechToCodePipeline│
│ - STT (Cloud/Moonshine)
│ - VoiceCommandRecognizer
│ - ILlamaController  │
└──────────┬──────────┘
           │ outputs
           ▼
┌─────────────────────┐
│ Generated Code      │
│ (shown in preview)  │
└─────────────────────┘
```

## Performance

| Stage | Latency |
|-------|---------|
| Audio capture | Real-time |
| STT (Cloud) | 500-800ms |
| STT (Moonshine) | 200-400ms |
| Intent recognition | 50-100ms |
| Code generation | 400-800ms |
| **Total (Cloud)** | **950-1700ms** |
| **Total (Offline)** | **650-1300ms** |

## License

GPL-3.0 - Same as CodeOnTheGo

## Configuring Offline Speech-to-Text Models

Starting from this version, you can configure offline STT models through the AI Settings:

### How to Add Offline STT Model:

1. **Navigate to AI Settings**:
   - Open Settings → Configure → AI & Voice

2. **Select Speech-to-Text Model**:
   - Scroll to "Speech-to-Text Model" card
   - Tap "Browse" to select your ONNX model directory
   - The directory should contain:
     - `preprocess.onnx`
     - `encode.int8.onnx`
     - `uncached_decode.int8.onnx`
     - `cached_decode.int8.onnx`
     - `tokens.txt`

3. **Configure STT Mode**:
   - Go to Settings → Configure → AI & Voice → Speech to Code
   - Under "Speech Recognition", select "Offline (Moonshine)"

### Supported STT Models:

- **Moonshine Tiny** (recommended for mobile):
  - Download from: https://huggingface.co/UsefulSensors/moonshine-tiny
  - Size: ~118MB total
  - Languages: English (more coming soon)
  - Accuracy: ~92%
  - Latency: 200-400ms

- **Moonshine Base**:
  - Larger model, better accuracy
  - Size: ~250MB
  - Slightly slower but more accurate

### Fallback Behavior:

If no offline model is configured, the system will:
1. Check the configured STT model path in AI Settings
2. Fall back to `/sdcard/Download/models/moonshine/` (default)
3. If offline model unavailable, use Cloud STT (requires internet)

### Benefits of Offline STT:

✅ Privacy - No audio sent to cloud  
✅ Faster - 200-400ms vs 500-800ms  
✅ Works offline - No internet needed  
✅ Cost-effective - No API calls  


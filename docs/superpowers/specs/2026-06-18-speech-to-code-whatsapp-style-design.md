# Speech-to-Code: WhatsApp-Style Voice Messages Design
**Date:** 2026-06-18
**Feature:** Voice-to-code with WhatsApp-style interaction
**Target:** CodeOnTheGo Android IDE
**Approach:** Viral-focused, familiar UX pattern

## Overview

This design document describes the implementation of speech-to-code functionality for CodeOnTheGo using a WhatsApp-style voice message interaction pattern. Users can speak natural language commands and see them transformed into actual code, with a preview-before-insert safety net that builds trust and creates shareable "wow" moments.

## Goals

- **Viral Appeal**: Familiar WhatsApp long-press pattern + preview panel = shareable moments
- **Trust Building**: Preview before insert prevents errors, encourages experimentation
- **Low Friction**: One long-press to start recording, minimal learning curve
- **Fast Execution**: 650-1300ms total latency (already achieved in convert-plugin-to-library)
- **Safety**: Preview panel prevents accidental code insertion
- **Marketing Story**: "Code like you text your friends"

## User Experience

### Visual Design

**1. Microphone Button (Top Toolbar)**
- Icon: 🎤 microphone vector drawable
- Position: Between search icon (🔍) and settings icon (⚙️)
- Size: 24dp x 24dp (consistent with other toolbar icons)
- Color States:
  - Idle: Gray (#808080)
  - Recording: Pulsing red (#E53935)
  - Processing: Animated blue spinner (#2196F3)

**2. Recording Overlay**
- Semi-transparent overlay over code editor (alpha: 0.85)
- Waveform visualization at center (animated based on audio amplitude)
- Transcription preview text at bottom (real-time if available)
- Cancel instruction: "← Slide left to cancel"
- Visual hierarchy: Dark overlay → Waveform → Text hints

**3. Preview Bottom Sheet**
- Material Design bottom sheet (slides up from bottom)
- Height: 60% of screen (max 400dp)
- Rounded top corners (16dp radius)
- Shadow elevation: 8dp
- Content sections:
  - **Header**: "Voice Command Result" (16sp, bold)
  - **Transcription Chip**: Pill-shaped background with voice command text
  - **Code Preview**: Syntax-highlighted code in scrollable view
  - **Action Bar**: Three buttons (Insert | Try Again | Cancel)

### Interaction Flow

#### Primary Flow (Long Press)

1. **User long-presses mic button**
   - Haptic feedback (vibrate 50ms)
   - Mic icon turns red and pulses
   - Recording overlay fades in over editor (200ms animation)
   - Waveform starts animating

2. **User speaks**: "create a RecyclerView adapter for user list"
   - Waveform reacts to voice amplitude in real-time
   - Audio captured at 16kHz PCM (Moonshine STT requirement)

3. **User releases button**
   - Recording overlay fades out (150ms)
   - Processing indicator appears (spinner + "Generating code...")
   - Background: SpeechToCodePipeline processes audio
     - STT (200-400ms)
     - Intent recognition (50-100ms)
     - LLM generation (400-800ms)
     - Total: 650-1300ms

4. **Preview panel slides up** (300ms spring animation)
   - Shows transcription: "create a RecyclerView adapter for user list"
   - Shows generated code with syntax highlighting
   - Three action buttons appear

5. **User taps "Insert"**
   - Green checkmark animation (success feedback)
   - Haptic feedback
   - Preview panel slides down (200ms)
   - Code appears at cursor with **typing animation** (20ms per character)
     - This creates the viral "wow" moment
     - Users love recording this for social media
   - Cursor moves to end of inserted code

#### Alternative Actions

**Try Again:**
- Immediately starts new recording
- Previous result discarded
- Same flow as step 1

**Cancel:**
- Preview panel slides down
- No code inserted
- Returns to IDLE state

**Slide to Cancel (during recording):**
- User slides finger left from mic button
- Recording cancelled
- Overlay fades out
- Audio discarded

#### Secondary Flow (Quick Tap)

1. **User taps mic button** (not long-press)
   - Opens voice mode panel (modal bottom sheet)
   - Shows:
     - Large mic button in center
     - "Tap to record" instruction
     - Recent voice commands list (last 5)
     - Settings button (gear icon)
   - Purpose: Longer dictation sessions, reviewing history

2. **User taps center mic button**
   - Toggle recording on/off
   - Same recording → processing → preview flow

3. **Settings gear icon**
   - Opens voice settings:
     - Enable/disable voice feature (toggle)
     - Show typing animation (toggle, default: ON)
     - Preview before insert (toggle, default: ON)
     - Voice language (dropdown, default: English)
     - LLM model selection (if multiple available)

### Visual States

```
State Machine:
IDLE → RECORDING → PROCESSING → PREVIEW → (INSERT | CANCEL) → IDLE
                     ↓
                   ERROR → Toast → IDLE
```

**IDLE:**
- Mic icon: gray, 100% opacity
- Overlay: hidden
- Preview: hidden

**RECORDING:**
- Mic icon: red, pulsing animation (scale 1.0 → 1.2 → 1.0, 800ms repeat)
- Overlay: visible, waveform animating
- Audio: capturing at 16kHz

**PROCESSING:**
- Mic icon: blue spinner animation
- Overlay: hidden
- Status text: "Generating code..." (centered on screen)

**PREVIEW:**
- Mic icon: gray (returns to idle)
- Overlay: hidden
- Bottom sheet: visible with code

**ERROR:**
- Mic icon: gray (returns to idle)
- Toast: error message (3 seconds)
- Example messages:
  - "No speech detected"
  - "Couldn't understand, try again"
  - "Generation timeout, try simpler command"

## Architecture

### Component Structure

```
┌──────────────────────────────────────────────────────┐
│  UI Layer                                            │
│  ├─ VoiceMicButton.kt                               │
│  │   └─ Handles long-press/tap detection           │
│  ├─ VoiceRecordingOverlay.kt                        │
│  │   └─ Waveform visualization                     │
│  ├─ VoicePreviewBottomSheet.kt                      │
│  │   └─ Code preview + action buttons              │
│  └─ WaveformVisualizer.kt                           │
│      └─ Custom view for audio visualization         │
└──────────────────────────────────────────────────────┘
                        ↓
┌──────────────────────────────────────────────────────┐
│  ViewModel Layer                                     │
│  └─ SpeechToCodeViewModel.kt                        │
│      ├─ LiveData<RecordingState>                    │
│      ├─ LiveData<ProcessingState>                   │
│      ├─ LiveData<PreviewData>                       │
│      └─ LiveData<ErrorState>                        │
└──────────────────────────────────────────────────────┘
                        ↓
┌──────────────────────────────────────────────────────┐
│  Service Layer (from convert-plugin-to-library)     │
│  └─ SpeechToCodePipeline.kt                         │
│      ├─ AudioRecorder (16kHz PCM capture)           │
│      ├─ Moonshine STT (200-400ms transcription)     │
│      ├─ VoiceCommandRecognizer (intent detection)   │
│      └─ LlamaController (code generation)           │
└──────────────────────────────────────────────────────┘
                        ↓
┌──────────────────────────────────────────────────────┐
│  Editor Integration                                  │
│  └─ IDEEditor.kt                                     │
│      ├─ insertTextWithAnimation()                   │
│      └─ getCurrentCursorPosition()                  │
└──────────────────────────────────────────────────────┘
```

### Data Models

**RecordingState:**
```kotlin
sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(
        val durationMs: Long,
        val amplitudes: List<Float> // for waveform
    ) : RecordingState()
    object Processing : RecordingState()
}
```

**PreviewData:**
```kotlin
data class PreviewData(
    val transcription: String,
    val generatedCode: String,
    val intent: String, // e.g., "CREATE_FUNCTION"
    val confidence: Float,
    val latencyMs: Long
)
```

**ErrorState:**
```kotlin
sealed class VoiceError {
    object NoSpeech : VoiceError()
    object RecognitionFailed : VoiceError()
    object GenerationTimeout : VoiceError()
    object PermissionDenied : VoiceError()
    data class Unknown(val message: String) : VoiceError()
}
```

### Integration Points

**1. Top Toolbar (menu_editor.xml)**
```xml
<item
    android:id="@+id/action_voice_code"
    android:icon="@drawable/ic_voice_mic"
    android:title="@string/voice_code"
    app:showAsAction="always" />
```

**2. EditorActivity.kt**
```kotlin
class EditorActivity : BaseEditorActivity() {
    private lateinit var speechViewModel: SpeechToCodeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize ViewModel
        speechViewModel = ViewModelProvider(this).get(SpeechToCodeViewModel::class.java)

        // Setup observers
        observeVoiceStates()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_voice_code -> {
                // Handle tap (open voice panel)
                showVoicePanel()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun observeVoiceStates() {
        speechViewModel.recordingState.observe(this) { state ->
            when (state) {
                is RecordingState.Idle -> hideRecordingOverlay()
                is RecordingState.Recording -> showRecordingOverlay(state.amplitudes)
                is RecordingState.Processing -> showProcessingIndicator()
            }
        }

        speechViewModel.previewData.observe(this) { data ->
            showPreviewBottomSheet(data)
        }

        speechViewModel.error.observe(this) { error ->
            handleVoiceError(error)
        }
    }
}
```

**3. SpeechToCodeViewModel.kt**
```kotlin
class SpeechToCodeViewModel(application: Application) : AndroidViewModel(application) {

    private val _recordingState = MutableLiveData<RecordingState>(RecordingState.Idle)
    val recordingState: LiveData<RecordingState> = _recordingState

    private val _previewData = MutableLiveData<PreviewData?>()
    val previewData: LiveData<PreviewData?> = _previewData

    private val _error = MutableLiveData<VoiceError?>()
    val error: LiveData<VoiceError?> = _error

    private var audioRecorder: AudioRecorder? = null
    private var pipeline: SpeechToCodePipeline? = null

    fun startRecording() {
        viewModelScope.launch {
            try {
                _recordingState.value = RecordingState.Recording(0L, emptyList())
                audioRecorder?.startRecording()
            } catch (e: Exception) {
                _error.value = VoiceError.Unknown(e.message ?: "Recording failed")
            }
        }
    }

    fun stopRecordingAndProcess() {
        viewModelScope.launch {
            try {
                _recordingState.value = RecordingState.Processing

                val audioBytes = audioRecorder?.stopRecording() ?: byteArrayOf()
                val result = pipeline?.processAudio(audioBytes)

                if (result != null && result.code.isNotEmpty()) {
                    _previewData.value = PreviewData(
                        transcription = result.command,
                        generatedCode = result.code,
                        intent = result.command,
                        confidence = result.confidence,
                        latencyMs = result.totalLatencyMs
                    )
                } else {
                    _error.value = VoiceError.NoSpeech
                }

                _recordingState.value = RecordingState.Idle
            } catch (e: Exception) {
                _error.value = VoiceError.Unknown(e.message ?: "Processing failed")
                _recordingState.value = RecordingState.Idle
            }
        }
    }

    fun insertCode(code: String, editor: IDEEditor) {
        viewModelScope.launch(Dispatchers.Main) {
            editor.insertTextWithAnimation(code)
            _previewData.value = null
        }
    }
}
```

## File Structure

### New Files

```
app/src/main/java/com/itsaky/androidide/ui/voice/
├── VoiceMicButton.kt              # Custom view for mic button with long-press
├── VoiceRecordingOverlay.kt       # Semi-transparent overlay with waveform
├── VoicePreviewBottomSheet.kt     # Bottom sheet for code preview
└── WaveformVisualizer.kt          # Custom waveform visualization

app/src/main/java/com/itsaky/androidide/viewmodel/
└── SpeechToCodeViewModel.kt       # ViewModel for managing voice states

app/src/main/java/com/itsaky/androidide/speech/
├── AudioRecorder.kt               # Copied from convert-plugin-to-library
├── SpeechToCodePipeline.kt        # Copied from convert-plugin-to-library
└── VoiceCommandRecognizer.kt      # Copied from convert-plugin-to-library

app/src/main/res/drawable/
├── ic_voice_mic.xml               # Microphone vector icon
├── ic_voice_waveform.xml          # Waveform icon for visualization
└── bg_voice_preview_sheet.xml     # Bottom sheet background

app/src/main/res/layout/
├── voice_preview_bottom_sheet.xml # Layout for preview panel
├── voice_recording_overlay.xml    # Layout for recording overlay
└── voice_mode_panel.xml           # Layout for quick-tap panel

app/src/main/res/values/
├── strings_voice.xml              # Voice feature strings
└── attrs_voice.xml                # Custom attributes for voice views
```

### Modified Files

```
app/src/main/res/menu/menu_editor.xml
  + Add microphone menu item

app/src/main/java/com/itsaky/androidide/activities/editor/EditorActivity.kt
  + Initialize SpeechToCodeViewModel
  + Setup voice observers
  + Handle mic button clicks

app/src/main/java/com/itsaky/androidide/editor/ui/IDEEditor.kt
  + Add insertTextWithAnimation() method

app/src/main/AndroidManifest.xml
  + Runtime permission handling for RECORD_AUDIO
```

## Data Flow

### End-to-End Flow

```
User Long-Press Mic
        ↓
VoiceMicButton detects gesture
        ↓
ViewModel.startRecording()
        ↓
AudioRecorder captures 16kHz PCM audio
        ↓
User releases button
        ↓
ViewModel.stopRecordingAndProcess()
        ↓
SpeechToCodePipeline.processAudio()
    ├─ Moonshine STT (200-400ms)
    ├─ VoiceCommandRecognizer (50-100ms)
    └─ LlamaController.generate() (400-800ms)
        ↓
ViewModel updates previewData LiveData
        ↓
VoicePreviewBottomSheet observes and displays
        ↓
User taps "Insert"
        ↓
ViewModel.insertCode()
        ↓
IDEEditor.insertTextWithAnimation()
        ↓
Code appears at cursor with typing effect
```

### Error Flow

```
Error occurs at any stage
        ↓
ViewModel updates error LiveData
        ↓
EditorActivity observes error
        ↓
Toast message displayed
        ↓
UI returns to IDLE state
```

## Performance Requirements

### Latency Targets (Already Achieved)

- **STT (Moonshine)**: 200-400ms
- **Intent Recognition**: 50-100ms
- **LLM Generation**: 400-800ms
- **Total Pipeline**: 650-1300ms (90th percentile)
- **UI Animation**: <300ms (feels instant)

### Memory Budget

- **Audio Buffer**: ~96KB for 3-second recording (16kHz, 16-bit, mono)
- **Preview Cache**: Max 20 recent results (~2MB)
- **Waveform Data**: ~1KB per recording (amplitude samples)
- **Total Addition**: <5MB memory overhead

### Battery Impact

- **Recording**: Minimal (passive microphone, no screen wake)
- **Processing**: Burst usage (1-2 seconds of CPU/LLM)
- **Idle**: Zero impact (no background services)

## Error Handling

### User-Facing Errors

**1. No Speech Detected**
- Toast: "No speech detected, try again"
- Cause: Silent audio or ambient noise only
- Recovery: Retry recording

**2. Recognition Failed**
- Toast: "Couldn't understand, please speak clearly"
- Cause: STT confidence < 0.6 or garbled audio
- Recovery: Retry recording, suggest speaking slower

**3. Generation Timeout**
- Toast: "Generation taking too long, try a simpler command"
- Cause: LLM timeout (>2 seconds)
- Recovery: Retry with simpler phrasing

**4. Permission Denied**
- Dialog: "Microphone permission required for voice coding"
- Action: Button to open app settings
- Cause: User denied RECORD_AUDIO permission
- Recovery: Prompt user to grant permission

**5. LLM Not Available**
- Toast: "AI model not loaded. Please load a model in settings."
- Cause: LlamaController not initialized
- Recovery: Navigate to AI settings

### Developer Logging

```kotlin
private const val TAG = "SpeechToCode"

// Log levels:
// DEBUG: State transitions, timing metrics
// INFO: User actions (start/stop recording, insert code)
// WARN: Recoverable errors (low confidence, timeout)
// ERROR: Critical failures (permission denied, crash)

Log.d(TAG, "Recording started")
Log.i(TAG, "Code inserted: ${code.take(50)}...")
Log.w(TAG, "Low confidence: ${result.confidence}")
Log.e(TAG, "Pipeline failed", exception)
```

## Testing Strategy

### Manual Testing Checklist

**Basic Flow:**
1. ✅ Long-press mic → see recording overlay
2. ✅ Speak "create function hello" → see preview
3. ✅ Tap "Insert" → code appears with typing animation
4. ✅ Verify code is syntactically correct
5. ✅ Cursor positioned at end of inserted code

**Error Scenarios:**
1. ✅ Long-press but stay silent → "No speech detected"
2. ✅ Speak gibberish → "Couldn't understand"
3. ✅ Complex command timeout → "Try simpler command"
4. ✅ Deny permission → Permission dialog appears

**Edge Cases:**
1. ✅ Slide to cancel during recording → No code inserted
2. ✅ Tap "Cancel" in preview → No code inserted
3. ✅ Rapid long-press/release → No crash
4. ✅ Recording during file switch → Recording cancelled
5. ✅ Background app during recording → Recording cancelled

**Performance:**
1. ✅ Total latency <2 seconds (650-1300ms target)
2. ✅ No UI jank during animation
3. ✅ Smooth waveform animation (60fps)
4. ✅ Preview panel smooth slide-up

### Viral Moment Testing

**Screen Recording Tests:**
1. Record full flow: voice → preview → typing animation
2. Verify typing animation is smooth and visible
3. Check preview panel looks professional on camera
4. Test with different code examples for variety

**Social Media Optimization:**
- 9:16 aspect ratio (portrait)
- Dark theme for better contrast
- Clear waveform visualization
- Obvious "Insert" button tap
- Satisfying typing animation

## Implementation Phases

### Phase 1: Core Infrastructure
- Create sibling branch `feature/speech-to-code-whatsapp`
- Copy speech components from `convert-plugin-to-library`
- Setup SpeechToCodeViewModel
- Add mic icon to toolbar
- Implement basic long-press detection

### Phase 2: Recording Flow
- Implement VoiceRecordingOverlay
- Add WaveformVisualizer
- Wire AudioRecorder to ViewModel
- Test recording start/stop

### Phase 3: Processing & Preview
- Integrate SpeechToCodePipeline
- Create VoicePreviewBottomSheet
- Implement preview → insert flow
- Add typing animation

### Phase 4: Polish & Error Handling
- Add all error states and toasts
- Implement slide-to-cancel
- Add haptic feedback
- Permission handling
- Testing and refinement

### Phase 5: Quick Tap & Settings
- Implement voice mode panel (quick tap)
- Add recent commands history
- Create settings panel
- Final testing and optimization

## Success Criteria

- ✅ Long-press mic starts recording smoothly
- ✅ Waveform visualizes audio in real-time
- ✅ Preview panel shows generated code within 2 seconds
- ✅ Typing animation is smooth and satisfying
- ✅ "Insert" button reliably inserts code at cursor
- ✅ Error messages are clear and helpful
- ✅ No memory leaks or crashes
- ✅ Feature feels polished and production-ready
- ✅ Screen recordings look impressive for marketing

## Viral Marketing Strategy

### Key Messaging

**"Code Like You Text"**
- Emphasize familiar WhatsApp pattern
- Show side-by-side: WhatsApp voice message vs CodeOnTheGo voice code

**Demo Videos (15-30 seconds each):**
1. "Build an Android app entirely by voice"
2. "From voice to code in 1 second"
3. "No keyboard needed - just speak your code"
4. "Voice coding on the train/bus/anywhere"

### Target Platforms

**Short-form video:**
- TikTok: 15s demo with trending audio
- Instagram Reels: 20s with captions
- Twitter/X: 30s with code example

**Long-form:**
- YouTube Shorts: Tutorial series
- Reddit r/androiddev: Demo + discussion
- Dev.to: Blog post with embedded videos

### Hashtags

#VoiceCoding #NoCodeNoKeyboard #AndroidDev #MobileDev #AIcoding #CodeOnTheGo #VoiceToCode #Accessibility

## Future Enhancements (Out of Scope)

- Multi-language support (Spanish, Hindi, Mandarin)
- Voice editing ("replace line 5 with...")
- Voice navigation ("scroll down", "go to function X")
- Collaborative voice coding (voice in pair programming)
- Voice-driven debugging ("add breakpoint here")
- Integration with GitHub Copilot Voice patterns
- Offline STT models (currently requires Moonshine)

## References

- Moonshine STT: https://github.com/usefulsensors/moonshine
- WhatsApp Voice UI Patterns: Material Design Guidelines
- Android SpeechRecognizer API: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Voice Coding Research: See web search sources in brainstorming notes

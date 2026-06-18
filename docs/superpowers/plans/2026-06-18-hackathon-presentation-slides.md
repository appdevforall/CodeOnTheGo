# Hackathon Technical Deep Dive Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create an HTML presentation showcasing three local LLM features (Speech-to-Code, Inline Suggestions, Vector Search) implemented during the hackathon (68 commits since 62fc4f19).

**Architecture:** Single-file HTML with embedded CSS, JavaScript, and Mermaid.js for diagrams. Dark theme, keyboard navigation, responsive design. Content extracted from actual codebase implementation (not specs).

**Tech Stack:** HTML5, CSS3, JavaScript (ES6), Mermaid.js (CDN), embedded images (base64 or external URLs)

## Global Constraints

- Single HTML file (no external dependencies except Mermaid.js CDN)
- Dark theme (#1a1a1a background, #e0e0e0 text, #2196F3 accents)
- All code examples from actual implementation files
- All diagrams must be valid Mermaid.js syntax
- Responsive design (works 1024px+ desktop, 768px tablet)
- Keyboard navigation (arrow keys, space, home/end)
- 18 slides total as designed

---

### Task 1: HTML Structure and Navigation

**Files:**
- Create: `hackathon-presentation.html`

**Interfaces:**
- Consumes: None (first task)
- Produces: HTML structure with slide container, navigation system, and keyboard event handlers

- [ ] **Step 1: Create HTML skeleton with slide navigation**

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Local LLM Hackathon - Technical Deep Dive</title>
    <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
</head>
<body>
    <div class="presentation">
        <div class="slide active" id="slide-0">
            <!-- Slide 1 content will go here -->
        </div>
        <!-- More slides will be added in subsequent tasks -->
    </div>

    <div class="controls">
        <span class="slide-number">1 / 18</span>
    </div>

    <script>
        let currentSlide = 0;
        const totalSlides = 18;

        function showSlide(n) {
            const slides = document.querySelectorAll('.slide');
            if (n >= totalSlides) currentSlide = totalSlides - 1;
            if (n < 0) currentSlide = 0;
            else currentSlide = n;

            slides.forEach((slide, index) => {
                slide.classList.remove('active');
                if (index === currentSlide) {
                    slide.classList.add('active');
                }
            });

            document.querySelector('.slide-number').textContent = `${currentSlide + 1} / ${totalSlides}`;
        }

        function nextSlide() {
            showSlide(currentSlide + 1);
        }

        function prevSlide() {
            showSlide(currentSlide - 1);
        }

        document.addEventListener('keydown', (e) => {
            if (e.key === 'ArrowRight' || e.key === ' ') {
                e.preventDefault();
                nextSlide();
            } else if (e.key === 'ArrowLeft') {
                e.preventDefault();
                prevSlide();
            } else if (e.key === 'Home') {
                e.preventDefault();
                showSlide(0);
            } else if (e.key === 'End') {
                e.preventDefault();
                showSlide(totalSlides - 1);
            }
        });

        // Initialize Mermaid
        mermaid.initialize({
            startOnLoad: true,
            theme: 'dark',
            themeVariables: {
                darkMode: true,
                background: '#1a1a1a',
                primaryColor: '#2196F3',
                primaryTextColor: '#e0e0e0',
                primaryBorderColor: '#2196F3',
                lineColor: '#2196F3',
                secondaryColor: '#424242',
                tertiaryColor: '#616161'
            }
        });
    </script>
</body>
</html>
```

- [ ] **Step 2: Verify navigation works**

Open `hackathon-presentation.html` in browser.
Expected: Single blank slide, controls showing "1 / 18", arrow keys do nothing yet (only 1 slide)

- [ ] **Step 3: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add HTML structure and navigation system"
```

---

### Task 2: CSS Styling System

**Files:**
- Modify: `hackathon-presentation.html` (add `<style>` in `<head>`)

**Interfaces:**
- Consumes: HTML structure from Task 1
- Produces: Complete CSS styling system with dark theme, slide transitions, code highlighting

- [ ] **Step 1: Add CSS in head section**

Insert before `</head>`:

```html
<style>
* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

body {
    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
    background: #0d0d0d;
    color: #e0e0e0;
    overflow: hidden;
}

.presentation {
    width: 100vw;
    height: 100vh;
    position: relative;
}

.slide {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    display: none;
    opacity: 0;
    transition: opacity 0.3s ease-in-out;
    padding: 60px 80px;
    background: #1a1a1a;
}

.slide.active {
    display: flex;
    flex-direction: column;
    opacity: 1;
}

h1 {
    font-size: 3.5rem;
    margin-bottom: 20px;
    color: #2196F3;
    text-align: center;
}

h2 {
    font-size: 2.5rem;
    margin-bottom: 30px;
    color: #2196F3;
    border-bottom: 3px solid #2196F3;
    padding-bottom: 10px;
}

h3 {
    font-size: 1.8rem;
    margin-top: 30px;
    margin-bottom: 15px;
    color: #64B5F6;
}

p, li {
    font-size: 1.3rem;
    line-height: 1.8;
    margin-bottom: 15px;
}

ul {
    margin-left: 40px;
}

code {
    background: #0d0d0d;
    padding: 2px 8px;
    border-radius: 4px;
    font-family: 'Courier New', monospace;
    color: #4CAF50;
    font-size: 1.1rem;
}

pre {
    background: #0d0d0d;
    padding: 20px;
    border-radius: 8px;
    overflow-x: auto;
    margin: 20px 0;
    border-left: 4px solid #2196F3;
}

pre code {
    background: none;
    padding: 0;
    font-size: 1rem;
    line-height: 1.6;
}

.controls {
    position: fixed;
    bottom: 30px;
    right: 30px;
    background: rgba(33, 150, 243, 0.1);
    padding: 10px 20px;
    border-radius: 25px;
    backdrop-filter: blur(10px);
    border: 1px solid #2196F3;
}

.slide-number {
    font-size: 1.1rem;
    color: #2196F3;
    font-weight: bold;
}

.mermaid {
    display: flex;
    justify-content: center;
    margin: 30px 0;
}

.highlight {
    color: #4CAF50;
    font-weight: bold;
}

.stats-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 30px;
    margin: 30px 0;
}

.stat-box {
    background: #0d0d0d;
    padding: 25px;
    border-radius: 12px;
    border: 2px solid #2196F3;
    text-align: center;
}

.stat-number {
    font-size: 3rem;
    color: #2196F3;
    font-weight: bold;
    display: block;
    margin-bottom: 10px;
}

.stat-label {
    font-size: 1.2rem;
    color: #9E9E9E;
}

.two-column {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 40px;
    margin: 20px 0;
}

.feature-icon {
    font-size: 4rem;
    text-align: center;
    margin-bottom: 20px;
}

@media (max-width: 1024px) {
    .slide {
        padding: 40px 50px;
    }
    h1 {
        font-size: 2.5rem;
    }
    h2 {
        font-size: 2rem;
    }
    .stats-grid {
        grid-template-columns: 1fr;
    }
    .two-column {
        grid-template-columns: 1fr;
    }
}
</style>
```

- [ ] **Step 2: Verify styling**

Refresh browser.
Expected: Slide has dark theme, proper padding, controls styled in bottom right

- [ ] **Step 3: Commit**

```bash
git add hackathon-presentation.html
git commit -m "style(presentation): add dark theme CSS and responsive layout"
```

---

### Task 3: Title and Overview Slides

**Files:**
- Modify: `hackathon-presentation.html` (update slide 0, add slides 1-2)

**Interfaces:**
- Consumes: CSS system from Task 2, navigation from Task 1
- Produces: Title slide, Overview slide with 3 feature summary

- [ ] **Step 1: Create title slide (slide 0)**

Replace the existing `<div class="slide active" id="slide-0">` content:

```html
<div class="slide active" id="slide-0">
    <h1 style="margin-top: 15%; margin-bottom: 40px;">🚀 Local LLM Hackathon</h1>
    <h2 style="border: none; text-align: center; color: #e0e0e0;">Leveraging Local LLMs for Mobile Development</h2>
    <p style="text-align: center; margin-top: 60px; font-size: 1.5rem; color: #9E9E9E;">
        Technical Deep Dive<br>
        June 18, 2026<br>
        68 commits • 3 features • 100% local inference
    </p>
</div>
```

- [ ] **Step 2: Create overview slide (slide 1)**

Add after slide 0:

```html
<div class="slide" id="slide-1">
    <h2>Hackathon Overview</h2>

    <div class="stats-grid">
        <div class="stat-box">
            <span class="stat-number">68</span>
            <span class="stat-label">Commits Since 62fc4f19</span>
        </div>
        <div class="stat-box">
            <span class="stat-number">3</span>
            <span class="stat-label">Major Features</span>
        </div>
        <div class="stat-box">
            <span class="stat-number">100%</span>
            <span class="stat-label">Local Inference</span>
        </div>
    </div>

    <h3>Features Implemented</h3>
    <ul>
        <li><span class="highlight">🎤 Speech-to-Code:</span> WhatsApp-style voice coding with 650-1300ms latency</li>
        <li><span class="highlight">✨ Inline Suggestions:</span> Copilot-style ghost text completions in Sora editor</li>
        <li><span class="highlight">🔍 Vector Search:</span> Semantic code search using embedding-based similarity</li>
    </ul>
</div>
```

- [ ] **Step 3: Create feature grid slide (slide 2)**

Add after slide 1:

```html
<div class="slide" id="slide-2">
    <h2>Three Pillars of Local LLM Integration</h2>

    <div class="two-column">
        <div>
            <div class="feature-icon">🎤</div>
            <h3>Speech-to-Code</h3>
            <p><strong>Problem:</strong> Typing on mobile is slow</p>
            <p><strong>Solution:</strong> Voice commands → STT → LLM → code insertion</p>
            <p><strong>Key Tech:</strong> Moonshine STT, Android SpeechRecognizer, local LLM pipeline</p>
        </div>

        <div>
            <div class="feature-icon">✨</div>
            <h3>Inline Suggestions</h3>
            <p><strong>Problem:</strong> No autocomplete for complex patterns</p>
            <p><strong>Solution:</strong> Ghost text multi-line completions</p>
            <p><strong>Key Tech:</strong> Sora editor components, custom rendering, LLM caching</p>
        </div>
    </div>

    <div style="margin-top: 30px; text-align: center;">
        <div class="feature-icon">🔍</div>
        <h3>Vector Search</h3>
        <p><strong>Problem:</strong> Keyword search misses semantic matches</p>
        <p><strong>Solution:</strong> Embedding-based similarity search</p>
        <p><strong>Key Tech:</strong> SQLiteIndex, cosine similarity, on-demand indexing</p>
    </div>
</div>
```

- [ ] **Step 4: Verify slides render and navigation works**

Refresh browser, use arrow keys to navigate.
Expected: 3 slides (title, overview, features), arrow keys switch between them, counter shows "1/18", "2/18", "3/18"

- [ ] **Step 5: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add title and overview slides"
```

---

### Task 4: Unified Architecture Diagram

**Files:**
- Modify: `hackathon-presentation.html` (add slide 3)

**Interfaces:**
- Consumes: Mermaid.js initialization from Task 1, CSS from Task 2
- Produces: Architecture diagram showing shared LLM infrastructure (slide 3)

- [ ] **Step 1: Add unified architecture slide with Mermaid diagram**

Add after slide 2:

```html
<div class="slide" id="slide-3">
    <h2>Unified Architecture: Shared LLM Core</h2>

    <div class="mermaid">
graph TB
    subgraph "Application Layer"
        A[Speech-to-Code Pipeline]
        B[Inline Suggestions Component]
        C[Vector Search Service]
    end

    subgraph "LLM Infrastructure"
        D[ILlamaController Interface]
        E[Model Loading & Management]
        F[Token Generation]
        G[Embedding Generation]
    end

    subgraph "Native Layer"
        H[llama.cpp]
        I[GGUF Models]
    end

    A --> D
    B --> D
    C --> G
    D --> F
    D --> G
    F --> E
    G --> E
    E --> H
    H --> I

    style A fill:#1565C0
    style B fill:#1565C0
    style C fill:#1565C0
    style D fill:#2E7D32
    style H fill:#D32F2F
    </div>

    <p style="text-align: center; margin-top: 30px; font-size: 1.2rem;">
        All three features leverage the same <code>ILlamaController</code> for inference<br>
        <span class="highlight">Benefit:</span> Shared model loading, unified memory management, consistent performance
    </p>
</div>
```

- [ ] **Step 2: Verify Mermaid diagram renders**

Refresh browser, navigate to slide 4.
Expected: Diagram displays with dark theme, boxes colored as specified, readable text

- [ ] **Step 3: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add unified architecture diagram"
```

---

### Task 5: Speech-to-Code Feature Slides (Part 1)

**Files:**
- Modify: `hackathon-presentation.html` (add slides 4-5)

**Interfaces:**
- Consumes: Previous slides and CSS
- Produces: Speech-to-Code overview and architecture slides (slides 4-5)

- [ ] **Step 1: Add Speech-to-Code overview slide**

Add after slide 3:

```html
<div class="slide" id="slide-4">
    <h2>🎤 Speech-to-Code: WhatsApp-Style Voice Coding</h2>

    <h3>Design Goals</h3>
    <ul>
        <li><strong>Viral Appeal:</strong> Familiar long-press pattern from WhatsApp</li>
        <li><strong>Trust Building:</strong> Preview-before-insert prevents errors</li>
        <li><strong>Fast Execution:</strong> 650-1300ms total latency (STT + LLM)</li>
        <li><strong>Safety:</strong> Preview panel before code insertion</li>
    </ul>

    <h3>User Flow</h3>
    <ol>
        <li>Long-press mic button → recording overlay with waveform</li>
        <li>Speak command: <em>"create a RecyclerView adapter for user list"</em></li>
        <li>Release button → processing (STT + Intent + LLM)</li>
        <li>Preview panel shows transcription + generated code</li>
        <li>Tap "Insert" → code appears with typing animation</li>
    </ol>

    <p style="margin-top: 30px;">
        <strong>Files:</strong> <code>SpeechToCodePipeline.kt</code>, <code>SpeechToCodeViewModel.kt</code>, <code>VoiceCodeAction.kt</code>
    </p>
</div>
```

- [ ] **Step 2: Add Speech-to-Code architecture slide**

Add after slide 4:

```html
<div class="slide" id="slide-5">
    <h2>Speech-to-Code: Architecture</h2>

    <div class="mermaid">
graph LR
    A[VoiceMicButton] --> B[SpeechToCodeViewModel]
    B --> C[SpeechToCodePipeline]
    C --> D[STT Engine]
    C --> E[VoiceCommandRecognizer]
    C --> F[ILlamaController]

    D --> G[CloudSTT: AndroidSpeechRecognizer]
    D --> H[OfflineSTT: MoonshineSTT]

    E --> I[Intent Patterns]
    F --> J[Code Generation]

    J --> K[PreviewBottomSheet]
    K --> L[IDEEditor.insertText]

    style C fill:#1565C0
    style D fill:#2E7D32
    style F fill:#2E7D32
    style K fill:#D32F2F
    </div>

    <h3>Key Components</h3>
    <div class="two-column">
        <div>
            <p><strong>SpeechToCodePipeline:</strong></p>
            <ul>
                <li>Coordinates STT → Intent → LLM</li>
                <li>Handles timeouts and errors</li>
                <li>Returns GenerationResult with latency</li>
            </ul>
        </div>
        <div>
            <p><strong>Dual STT Support:</strong></p>
            <ul>
                <li>Cloud: Android SpeechRecognizer</li>
                <li>Offline: Moonshine (200-400ms)</li>
                <li>User preference in AI Settings</li>
            </ul>
        </div>
    </div>
</div>
```

- [ ] **Step 3: Verify slides render correctly**

Navigate to slides 5-6.
Expected: Content displays, Mermaid diagram renders, two-column layout works

- [ ] **Step 4: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add speech-to-code overview and architecture slides"
```

---

### Task 6: Speech-to-Code Feature Slides (Part 2)

**Files:**
- Modify: `hackathon-presentation.html` (add slides 6-7)

**Interfaces:**
- Consumes: Previous slides
- Produces: Pipeline flow diagram and performance metrics slides (slides 6-7)

- [ ] **Step 1: Add pipeline flow diagram slide**

Add after slide 5:

```html
<div class="slide" id="slide-6">
    <h2>Speech-to-Code: Pipeline Flow</h2>

    <div class="mermaid">
sequenceDiagram
    participant User
    participant UI
    participant Pipeline
    participant STT
    participant LLM
    participant Editor

    User->>UI: Long press mic button
    UI->>UI: Show recording overlay + waveform
    User->>UI: Speak command
    User->>UI: Release button
    UI->>Pipeline: processAudio(audioBytes)
    Pipeline->>STT: transcribe(audioBytes)
    STT-->>Pipeline: TranscriptionResult(200-400ms)
    Pipeline->>Pipeline: VoiceCommandRecognizer(50-100ms)
    Pipeline->>LLM: generate(prompt)
    LLM-->>Pipeline: code(400-800ms)
    Pipeline-->>UI: GenerationResult
    UI->>UI: Show PreviewBottomSheet
    User->>UI: Tap "Insert"
    UI->>Editor: insertTextWithAnimation(code)
    Editor->>User: Code appears with typing effect
    </div>

    <p style="text-align: center; margin-top: 20px;">
        <strong>Total Latency:</strong> 650-1300ms (90th percentile)<br>
        <strong>Bottleneck:</strong> LLM generation (400-800ms)
    </p>
</div>
```

- [ ] **Step 2: Add performance metrics slide**

Add after slide 6:

```html
<div class="slide" id="slide-7">
    <h2>Speech-to-Code: Performance & UX</h2>

    <h3>Latency Breakdown (from SpeechToCodePipeline.kt)</h3>
    <div class="stats-grid">
        <div class="stat-box">
            <span class="stat-number">200-400ms</span>
            <span class="stat-label">Moonshine STT</span>
        </div>
        <div class="stat-box">
            <span class="stat-number">50-100ms</span>
            <span class="stat-label">Intent Recognition</span>
        </div>
        <div class="stat-box">
            <span class="stat-number">400-800ms</span>
            <span class="stat-label">LLM Code Generation</span>
        </div>
    </div>

    <h3>State Machine</h3>
    <pre><code>IDLE → RECORDING → PROCESSING → PREVIEW → (INSERT | CANCEL) → IDLE
                ↓
              ERROR → Toast → IDLE</code></pre>

    <h3>Key Achievements</h3>
    <ul>
        <li>✅ Preview-before-insert builds user trust</li>
        <li>✅ Typing animation creates viral "wow" moment</li>
        <li>✅ Dual STT support (cloud + offline)</li>
        <li>✅ Spanish language support via Android SpeechRecognizer</li>
    </ul>
</div>
```

- [ ] **Step 3: Verify sequence diagram renders**

Navigate to slide 7.
Expected: Mermaid sequence diagram displays interactions correctly

- [ ] **Step 4: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add speech-to-code pipeline and performance slides"
```

---

### Task 7: Inline Suggestions Feature Slides (Part 1)

**Files:**
- Modify: `hackathon-presentation.html` (add slides 8-9)

**Interfaces:**
- Consumes: Previous slides and CSS
- Produces: Inline suggestions overview and architecture slides (slides 8-9)

- [ ] **Step 1: Add inline suggestions overview slide**

Add after slide 7:

```html
<div class="slide" id="slide-8">
    <h2>✨ Inline Suggestions: Copilot-Style Completions</h2>

    <h3>Design Goals</h3>
    <ul>
        <li><strong>Seamless UX:</strong> Ghost text appears automatically after 3 characters + 300ms idle</li>
        <li><strong>Manual Control:</strong> Ctrl+Space or toolbar button for on-demand suggestions</li>
        <li><strong>Multi-line Support:</strong> 3-5 lines of contextual code completion</li>
        <li><strong>Local Performance:</strong> Optimized for 4GB devices with caching</li>
    </ul>

    <h3>User Interaction</h3>
    <ul>
        <li><strong>Trigger:</strong> Auto (3 chars + 300ms) or Manual (Ctrl+Space)</li>
        <li><strong>Display:</strong> Semi-transparent gray ghost text (#808080 @ 40% opacity)</li>
        <li><strong>Accept:</strong> Tab key inserts entire suggestion</li>
        <li><strong>Dismiss:</strong> Esc key or continue typing incompatible characters</li>
    </ul>

    <p style="margin-top: 30px;">
        <strong>Implementation:</strong> Extends Sora editor via <code>EditorBuiltinComponent</code>
    </p>
</div>
```

- [ ] **Step 2: Add inline suggestions architecture slide**

Add after slide 8:

```html
<div class="slide" id="slide-9">
    <h2>Inline Suggestions: Sora Component Architecture</h2>

    <div class="mermaid">
graph TB
    A[IDEEditor] --> B[InlineSuggestionComponent]
    B --> C[GhostTextRenderer]
    B --> D[SuggestionProvider]
    B --> E[State Machine]

    C --> F[Canvas Drawing]
    C --> G[Multi-line Layout]

    D --> H[LLM Repository]
    D --> I[Context Builder]
    D --> J[LRU Cache]

    E --> K[IDLE]
    E --> L[WAITING]
    E --> M[REQUESTING]
    E --> N[SHOWING]
    E --> O[ACCEPTING]

    F --> P[Sora onDraw Hook]
    H --> Q[ILlamaController]

    style B fill:#1565C0
    style C fill:#2E7D32
    style D fill:#2E7D32
    style Q fill:#D32F2F
    </div>

    <p style="text-align: center; margin-top: 20px;">
        <strong>Key Insight:</strong> Follows Sora's component pattern like <code>EditorAutoCompletion</code><br>
        Renders ghost text via custom <code>Canvas</code> drawing, not DOM manipulation
    </p>
</div>
```

- [ ] **Step 3: Verify Mermaid diagram renders**

Navigate to slides 9-10.
Expected: Architecture diagram displays component relationships clearly

- [ ] **Step 4: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add inline suggestions overview and architecture slides"
```

---

### Task 8: Inline Suggestions Feature Slides (Part 2)

**Files:**
- Modify: `hackathon-presentation.html` (add slides 10-11)

**Interfaces:**
- Consumes: Previous slides
- Produces: Rendering pipeline and caching strategy slides (slides 10-11)

- [ ] **Step 1: Add rendering pipeline slide**

Add after slide 9:

```html
<div class="slide" id="slide-10">
    <h2>Inline Suggestions: Ghost Text Rendering</h2>

    <h3>GhostTextRenderer Implementation</h3>
    <pre><code>class GhostTextRenderer(private val editor: IDEEditor) {
    private val ghostPaint: Paint = Paint().apply {
        color = Color.argb(102, 128, 128, 128)  // 40% opacity
        textSize = editor.textSizePx
        typeface = editor.typefaceText
    }

    fun drawSuggestion(canvas: Canvas, suggestion: SuggestionData) {
        // 1. Calculate cursor position in screen coordinates
        // 2. Split multi-line suggestions
        // 3. Render each line with proper indentation
        // 4. Clip to visible region
    }
}</code></pre>

    <h3>Rendering Flow</h3>
    <ol>
        <li>Hook into Sora's <code>onDraw</code> lifecycle</li>
        <li>Calculate ghost text position from cursor coordinates</li>
        <li>Measure text using editor's font/size</li>
        <li>Draw each line with vertical offset</li>
        <li>Apply transparency and clipping</li>
    </ol>
</div>
```

- [ ] **Step 2: Add caching and provider slide**

Add after slide 10:

```html
<div class="slide" id="slide-11">
    <h2>Inline Suggestions: LLM Integration & Caching</h2>

    <h3>SuggestionProvider Strategy</h3>
    <div class="two-column">
        <div>
            <p><strong>Context Building:</strong></p>
            <pre><code>data class SuggestionContext(
    val fileContent: String,
    val cursorPosition: Position,
    val language: String,
    val previousLines: List<String>,
    val currentLinePrefix: String
)</code></pre>
        </div>
        <div>
            <p><strong>Caching:</strong></p>
            <ul>
                <li>LRU cache: 20 recent suggestions</li>
                <li>Key: Hash(file, cursor, context)</li>
                <li>Expiry: 30s or on file edit</li>
                <li>Prevents redundant LLM calls</li>
            </ul>
        </div>
    </div>

    <h3>Performance Optimizations</h3>
    <ul>
        <li><strong>Debouncing:</strong> 300ms idle before LLM request</li>
        <li><strong>Cancellation:</strong> Cancel in-flight requests on new input</li>
        <li><strong>Thread Safety:</strong> All LLM calls on background thread</li>
        <li><strong>Memory:</strong> Cache cleared on low memory warning</li>
    </ul>
</div>
```

- [ ] **Step 3: Verify code blocks format correctly**

Navigate to slides 11-12.
Expected: Code blocks have proper syntax highlighting, readable in dark theme

- [ ] **Step 4: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add inline suggestions rendering and caching slides"
```

---

### Task 9: Vector Search Feature Slides (Part 1)

**Files:**
- Modify: `hackathon-presentation.html` (add slides 12-13)

**Interfaces:**
- Consumes: Previous slides
- Produces: Vector search overview and architecture slides (slides 12-13)

- [ ] **Step 1: Add vector search overview slide**

Add after slide 11:

```html
<div class="slide" id="slide-12">
    <h2>🔍 Vector Search: Semantic Code Search</h2>

    <h3>The Problem with Keyword Search</h3>
    <ul>
        <li>Search "authentication" → misses "login", "signin", "verify user"</li>
        <li>Search "database" → misses "repository", "DAO", "data access"</li>
        <li>No understanding of semantic similarity</li>
    </ul>

    <h3>Embedding-Based Solution</h3>
    <ul>
        <li><strong>Code Chunking:</strong> Split files into 200-500 char semantic chunks</li>
        <li><strong>Embedding Generation:</strong> Convert chunks to 384-dim vectors via local LLM</li>
        <li><strong>Similarity Search:</strong> Cosine similarity between query embedding and code embeddings</li>
        <li><strong>On-Demand Indexing:</strong> Index project on first search, cache in SQLite</li>
    </ul>

    <p style="margin-top: 30px;">
        <strong>Result:</strong> Search "auth" finds authentication logic even without exact keyword match
    </p>
</div>
```

- [ ] **Step 2: Add vector search architecture slide**

Add after slide 12:

```html
<div class="slide" id="slide-13">
    <h2>Vector Search: Module Architecture</h2>

    <div class="mermaid">
graph TB
    subgraph "App Module"
        A[EditorViewModel]
        B[VectorSearchCommand]
        C[SearchResultFragment]
    end

    subgraph "vector-search Module"
        D[VectorSearchService]
        E[CodeChunker]
        F[CodeEmbedding]
        G[VectorMath]
        H[CodeEmbeddingDescriptor]
    end

    subgraph "Infrastructure"
        I[SQLiteIndex]
        J[ILlamaController]
    end

    A --> B
    B --> D
    D --> E
    D --> G
    D --> J
    E --> F
    F --> I
    H --> I
    C --> A

    style D fill:#1565C0
    style E fill:#2E7D32
    style J fill:#D32F2F
    style I fill:#F57C00
    </div>

    <p style="text-align: center; margin-top: 20px;">
        <strong>Module Structure:</strong> Standalone <code>vector-search</code> library<br>
        Dependencies: <code>llama-api</code>, <code>lsp:indexing</code>
    </p>
</div>
```

- [ ] **Step 3: Verify diagram renders**

Navigate to slides 13-14.
Expected: Module architecture clearly shows dependencies and data flow

- [ ] **Step 4: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add vector search overview and architecture slides"
```

---

### Task 10: Vector Search Feature Slides (Part 2)

**Files:**
- Modify: `hackathon-presentation.html` (add slides 14-15)

**Interfaces:**
- Consumes: Previous slides
- Produces: Indexing flow and search pipeline slides (slides 14-15)

- [ ] **Step 1: Add indexing flow diagram slide**

Add after slide 13:

```html
<div class="slide" id="slide-14">
    <h2>Vector Search: On-Demand Indexing</h2>

    <div class="mermaid">
flowchart TD
    A[User enters search query] --> B{Project indexed?}
    B -->|No| C[Show 'Indexing project...']
    C --> D[Scan for .kt/.java/.xml files]
    D --> E[For each file]
    E --> F[CodeChunker.chunkFile]
    F --> G[Generate embeddings via LLM]
    G --> H[Store CodeEmbedding in SQLiteIndex]
    H --> I{More files?}
    I -->|Yes| E
    I -->|No| J[Indexing complete]

    B -->|Yes| K[Load embeddings from SQLite]
    J --> K
    K --> L[VectorSearchService.search]
    L --> M[Generate query embedding]
    M --> N[Calculate cosine similarity]
    N --> O[Sort by similarity]
    O --> P[Return top 20 results]
    P --> Q[Display in SearchResultFragment]

    style C fill:#FF9800
    style G fill:#2E7D32
    style L fill:#1565C0
    </div>
</div>
```

- [ ] **Step 2: Add search pipeline slide**

Add after slide 14:

```html
<div class="slide" id="slide-15">
    <h2>Vector Search: Search Pipeline</h2>

    <h3>VectorSearchService.search() Implementation</h3>
    <pre><code>suspend fun search(
    query: String,
    limit: Int = 20,
    threshold: Float = 0.0f
): List<CodeEmbedding> {
    return withContext(Dispatchers.Default) {
        // 1. Generate embedding for query
        val queryEmbedding = llamaController.generateEmbedding(query)

        // 2. Load all code embeddings from SQLite
        val allEmbeddings = index.query(IndexQuery.ALL)

        // 3. Calculate cosine similarity for each
        allEmbeddings.map { codeEmbedding ->
            val similarity = VectorMath.cosineSimilarity(
                queryEmbedding,
                codeEmbedding.embedding
            )
            Pair(codeEmbedding, similarity)
        }
        // 4. Filter by threshold, sort, take top K
        .filter { (_, score) -> score >= threshold }
        .sortedByDescending { (_, score) -> score }
        .take(limit)
        .map { (embedding, _) -> embedding }
    }
}</code></pre>

    <p style="margin-top: 20px;">
        <strong>Performance:</strong> 100-500ms for 1000 embeddings (cosine similarity is fast!)
    </p>
</div>
```

- [ ] **Step 3: Verify flowchart and code display**

Navigate to slides 15-16.
Expected: Flowchart shows indexing decision path, code is readable

- [ ] **Step 4: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add vector search indexing and pipeline slides"
```

---

### Task 11: Technical Achievements and Q&A Slides

**Files:**
- Modify: `hackathon-presentation.html` (add slides 16-17)

**Interfaces:**
- Consumes: Previous slides
- Produces: Summary of achievements and Q&A slide (slides 16-17)

- [ ] **Step 1: Add technical achievements slide**

Add after slide 15:

```html
<div class="slide" id="slide-16">
    <h2>🏆 Technical Achievements</h2>

    <div class="stats-grid">
        <div class="stat-box">
            <span class="stat-number">68</span>
            <span class="stat-label">Commits</span>
        </div>
        <div class="stat-box">
            <span class="stat-number">650ms</span>
            <span class="stat-label">Min Speech Latency</span>
        </div>
        <div class="stat-box">
            <span class="stat-number">100%</span>
            <span class="stat-label">Local Inference</span>
        </div>
    </div>

    <h3>Key Innovations</h3>
    <div class="two-column">
        <div>
            <p><strong>🎤 Speech-to-Code:</strong></p>
            <ul>
                <li>Dual STT support (Cloud + Moonshine)</li>
                <li>Preview-before-insert UX pattern</li>
                <li>Spanish language support</li>
                <li>Sub-second end-to-end latency</li>
            </ul>
        </div>
        <div>
            <p><strong>✨ Inline Suggestions:</strong></p>
            <ul>
                <li>Custom Sora component architecture</li>
                <li>Ghost text canvas rendering</li>
                <li>LRU caching for performance</li>
                <li>Multi-line completions</li>
            </ul>
        </div>
    </div>

    <div style="margin-top: 30px;">
        <p><strong>🔍 Vector Search:</strong></p>
        <ul>
            <li>Standalone <code>vector-search</code> module</li>
            <li>On-demand indexing strategy</li>
            <li>384-dim embeddings via local LLM</li>
            <li>Cosine similarity search (100-500ms)</li>
        </ul>
    </div>
</div>
```

- [ ] **Step 2: Add Q&A slide**

Add after slide 16:

```html
<div class="slide" id="slide-17">
    <h1 style="margin-top: 20%; text-align: center;">Questions?</h1>

    <div style="margin-top: 80px; text-align: center;">
        <h3>Key Repositories</h3>
        <p style="font-size: 1.2rem; margin-top: 30px;">
            <strong>CodeOnTheGo:</strong> Android IDE with local LLM integration<br>
            <strong>Commits:</strong> 62fc4f19..HEAD (68 commits)<br>
            <strong>Date Range:</strong> June 18, 2026
        </p>

        <div style="margin-top: 60px; font-size: 1.1rem; color: #9E9E9E;">
            <p>Thank you!</p>
            <p>🚀 Local LLMs on Mobile • 100% On-Device Inference • Zero Cloud Dependencies</p>
        </div>
    </div>
</div>
```

- [ ] **Step 3: Verify final slides**

Navigate to slides 17-18.
Expected: Achievement stats display, Q&A slide is clean and centered

- [ ] **Step 4: Update total slides count in JavaScript**

Verify the `totalSlides` constant at the top of `<script>` section is still `18`.

- [ ] **Step 5: Commit**

```bash
git add hackathon-presentation.html
git commit -m "feat(presentation): add technical achievements and Q&A slides"
```

---

### Task 12: Final Polish and Testing

**Files:**
- Modify: `hackathon-presentation.html` (add meta tags, fix any layout issues)
- Create: `README-presentation.md` (usage instructions)

**Interfaces:**
- Consumes: All previous slides (0-17)
- Produces: Polished presentation ready for deployment, usage documentation

- [ ] **Step 1: Add comprehensive meta tags and accessibility**

In `<head>` section, add before `<title>`:

```html
<meta name="description" content="Local LLM Hackathon Technical Deep Dive - Speech-to-Code, Inline Suggestions, Vector Search">
<meta name="author" content="CodeOnTheGo Team">
<meta name="keywords" content="local LLM, speech-to-code, inline suggestions, vector search, mobile development">
```

- [ ] **Step 2: Add keyboard shortcut hint overlay**

Add before closing `</body>` tag:

```html
<div style="position: fixed; bottom: 30px; left: 30px; background: rgba(33, 150, 243, 0.1); padding: 10px 15px; border-radius: 8px; font-size: 0.9rem; color: #9E9E9E; backdrop-filter: blur(10px);">
    ← → Space: Navigate | Home/End: Jump
</div>
```

- [ ] **Step 3: Test all slides and navigation**

Manual testing checklist:
1. Open `hackathon-presentation.html` in Chrome/Firefox
2. Press Right Arrow 18 times → should go through all slides
3. Press Left Arrow → should go back
4. Press Home → should jump to slide 1
5. Press End → should jump to slide 18
6. Verify all Mermaid diagrams render correctly
7. Check responsive layout at 1024px, 1440px, 1920px widths
8. Verify no console errors

Expected: All slides navigate smoothly, diagrams render, no errors

- [ ] **Step 4: Create usage documentation**

Create file:

```markdown
# Hackathon Presentation - Usage Guide

## Overview

HTML-based technical presentation for Local LLM Hackathon showcasing:
- 🎤 Speech-to-Code (WhatsApp-style voice coding)
- ✨ Inline Suggestions (Copilot-style completions)
- 🔍 Vector Search (Semantic code search)

## Usage

### Opening the Presentation

1. Open `hackathon-presentation.html` in any modern browser
2. Presentation starts on slide 1/18

### Navigation

- **Next Slide:** Right Arrow, Space
- **Previous Slide:** Left Arrow
- **First Slide:** Home
- **Last Slide:** End

### Features

- 18 slides with technical deep dive content
- Dark theme optimized for presentations
- Mermaid.js diagrams for architecture visualization
- Responsive design (1024px+)
- Keyboard-only navigation

## Slides Breakdown

1. Title
2. Overview (stats and feature list)
3. Feature grid (3 pillars)
4. Unified architecture diagram
5. Speech-to-Code: Overview
6. Speech-to-Code: Architecture
7. Speech-to-Code: Pipeline flow
8. Speech-to-Code: Performance
9. Inline Suggestions: Overview
10. Inline Suggestions: Architecture
11. Inline Suggestions: Rendering
12. Inline Suggestions: Caching
13. Vector Search: Overview
14. Vector Search: Architecture
15. Vector Search: Indexing flow
16. Vector Search: Search pipeline
17. Technical achievements
18. Q&A

## Technical Details

- **File:** Single HTML file (no external dependencies except Mermaid.js CDN)
- **Size:** ~30-40 KB
- **Dependencies:** Mermaid.js v10 (CDN)
- **Browser Support:** Chrome 90+, Firefox 88+, Safari 14+

## Presenting

1. Full screen (F11 or browser fullscreen)
2. Use presenter mode if available
3. Advance slides with Space bar for smooth flow
4. Diagrams are interactive (Mermaid.js)

## Customization

All content is in the single `hackathon-presentation.html` file:
- Modify `<style>` for visual changes
- Edit slide `<div>` elements for content
- Update Mermaid diagrams by editing diagram code blocks
```

Save to `README-presentation.md`

- [ ] **Step 5: Final validation**

Run through entire presentation one more time:
1. Verify all 18 slides display correctly
2. Check all Mermaid diagrams render
3. Verify code blocks are readable
4. Test keyboard navigation
5. Check slide counter updates correctly

Expected: Flawless presentation experience, no visual bugs

- [ ] **Step 6: Commit final version**

```bash
git add hackathon-presentation.html README-presentation.md
git commit -m "feat(presentation): finalize hackathon technical deep dive slides

- Add 18 slides covering all 3 features
- Include architecture diagrams with Mermaid.js
- Add keyboard navigation and responsive design
- Include usage documentation"
```

---

## Execution Complete

The implementation plan is now ready for execution. The presentation will be a single HTML file with:

- 18 slides covering title, overview, and deep dives into all 3 features
- Mermaid.js diagrams for architecture visualization
- Dark theme optimized for technical presentations
- Keyboard navigation
- Actual code examples from the codebase
- Performance metrics from real implementation

All content is based on the actual codebase (InlineSuggestionComponent.kt, SpeechToCodePipeline.kt, VectorSearchService.kt) rather than just the design specs.

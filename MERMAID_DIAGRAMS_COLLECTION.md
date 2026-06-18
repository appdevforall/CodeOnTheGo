# Mermaid Diagrams Collection
# Convert Plugin to Library Branch

All diagrams extracted for standalone use. Each diagram can be rendered individually.

---

## Diagram 1: High-Level System Architecture

**Purpose:** Shows the overall architecture after plugin-to-library conversion

```mermaid
graph TB
    subgraph "Android IDE Application"
        Editor[Editor Module]
        UI[UI Layer]
        Search[Search Interface]
    end

    subgraph "AI Chat Library (Converted from Plugin)"
        AiServices[AI Services Manager]

        subgraph "Core AI Services"
            Chat[AI Chat Service]
            Vector[Vector Search Service]
            Voice[Voice-to-Code Service]
            Smart[Smart Completion Service]
        end

        subgraph "Shared Infrastructure"
            Llama[LLaMA Controller]
            Embedding[Embedding Engine]
            Models[Model Registry]
        end
    end

    subgraph "External Resources"
        LocalModels[(Local ML Models)]
        Gemini[Gemini API]
    end

    Editor --> AiServices
    UI --> AiServices
    Search --> AiServices

    AiServices --> Chat
    AiServices --> Vector
    AiServices --> Voice
    AiServices --> Smart

    Chat --> Llama
    Vector --> Embedding
    Voice --> Llama
    Smart --> Llama

    Llama --> Models
    Embedding --> Models
    Models --> LocalModels
    Chat -.Optional.-> Gemini

    style AiServices fill:#4CAF50
    style Chat fill:#2196F3
    style Vector fill:#FF9800
    style Voice fill:#9C27B0
    style Smart fill:#F44336
```

---

## Diagram 2: AI Chat User Flow

**Purpose:** Demonstrates the interaction flow when a developer uses AI Chat

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant UI as Chat UI
    participant VM as ChatViewModel
    participant Agent as Agentic Runner
    participant Tools as Tool System
    participant Editor as IDE Editor

    Dev->>UI: Ask question about code
    UI->>VM: Send message
    VM->>Agent: Process with context

    Agent->>Tools: Execute ReadFile tool
    Tools-->>Agent: Return file content

    Agent->>Tools: Execute Analyze tool
    Tools-->>Agent: Return analysis

    Agent->>VM: Generate response
    VM->>UI: Display answer
    UI->>Dev: Show response with code snippets

    Note over Dev,Editor: Optional: Apply suggested changes
    Dev->>UI: Request code edit
    UI->>Agent: Execute edit
    Agent->>Editor: Apply changes
    Editor-->>UI: Confirm applied
    UI->>Dev: Show success
```

---

## Diagram 3: AI Chat Architecture

**Purpose:** Shows the internal architecture of the AI Chat service

```mermaid
graph LR
    subgraph "User Interface"
        ChatUI[Chat Fragment]
        Input[Message Input]
        History[Conversation History]
    end

    subgraph "State Management"
        VM[Chat ViewModel]
        Session[Session State]
        Storage[Chat Storage]
    end

    subgraph "AI Backend"
        Router[Capability Router]
        Gemini[Gemini Repository]
        Local[Local LLM Repository]
    end

    subgraph "Agentic System"
        Planner[Planning Agent]
        Executor[Execution Agent]
        Critic[Critic Agent]
    end

    subgraph "Tool Ecosystem"
        ReadTool[Read File]
        WriteTool[Write File]
        SearchTool[Search Code]
        DiagTool[Diagnostics]
    end

    ChatUI --> Input
    ChatUI --> History
    Input --> VM
    VM --> Session
    Session --> Storage

    VM --> Router
    Router --> Gemini
    Router --> Local

    Gemini --> Planner
    Local --> Planner

    Planner --> Executor
    Executor --> Critic
    Critic -.Feedback.-> Executor

    Executor --> ReadTool
    Executor --> WriteTool
    Executor --> SearchTool
    Executor --> DiagTool

    style ChatUI fill:#2196F3
    style VM fill:#4CAF50
    style Router fill:#FF9800
    style Planner fill:#9C27B0
```

---

## Diagram 4: Vector Search User Journey

**Purpose:** End-to-end user journey for semantic code search

```mermaid
graph TD
    Start([Developer needs to find<br/>authentication code]) --> Query[Enter natural language query:<br/>'how is user login handled']

    Query --> Index{Is codebase<br/>indexed?}

    Index -->|No| Scanning[Scan project files]
    Scanning --> Chunking[Chunk code into<br/>semantic blocks]
    Chunking --> Embedding[Generate embeddings<br/>using ML model]
    Embedding --> Store[Store in vector index]
    Store --> Search

    Index -->|Yes| Search[Search vector index]

    Search --> Rank[Rank by cosine similarity]
    Rank --> Display[Display top 20 results]

    Display --> Result1[auth/LoginHandler.kt<br/>Similarity: 94%]
    Display --> Result2[security/AuthService.kt<br/>Similarity: 87%]
    Display --> Result3[api/AuthController.kt<br/>Similarity: 82%]

    Result1 --> Navigate{Developer clicks result}
    Result2 --> Navigate
    Result3 --> Navigate

    Navigate --> Editor[Jump to file and line<br/>in editor]
    Editor --> End([Code found!])

    style Start fill:#4CAF50
    style Query fill:#2196F3
    style Search fill:#FF9800
    style End fill:#4CAF50
```

---

## Diagram 5: Vector Search Architecture

**Purpose:** Technical architecture of the vector search system

```mermaid
graph TB
    subgraph "Search UI"
        SearchInput[Search Input Field]
        ResultsList[Search Results List]
        ProgressBar[Loading Indicator]
    end

    subgraph "State Management"
        SearchVM[Search ViewModel]
        LiveData[Observable State]
    end

    subgraph "Indexing Pipeline"
        Scanner[Project Code Scanner]
        Indexer[Code Indexing Manager]
        Chunker[Code Chunker]
    end

    subgraph "Vector Search Core"
        VectorService[Vector Search Service]
        EmbeddingService[Embedding Indexing Service]
        VectorMath[Vector Math / Cosine Similarity]
    end

    subgraph "ML Infrastructure"
        EmbeddingModel[Embedding Model<br/>200-300MB]
        LlamaCtrl[LLaMA Controller]
    end

    subgraph "Navigation"
        NavService[Editor Navigation Service]
        EditorAPI[IDE Editor API]
    end

    SearchInput --> SearchVM
    SearchVM --> LiveData
    LiveData --> ResultsList
    LiveData --> ProgressBar

    SearchVM --> VectorService
    VectorService --> EmbeddingService
    EmbeddingService --> VectorMath

    VectorService --> Scanner
    Scanner --> Indexer
    Indexer --> Chunker
    Chunker --> EmbeddingService

    EmbeddingService --> LlamaCtrl
    LlamaCtrl --> EmbeddingModel

    ResultsList --> NavService
    NavService --> EditorAPI

    style SearchInput fill:#2196F3
    style SearchVM fill:#4CAF50
    style VectorService fill:#FF9800
    style EmbeddingModel fill:#9C27B0
```

---

## Diagram 6: Voice-to-Code User Flow

**Purpose:** Sequence of voice command to code generation

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant Editor as Editor UI
    participant Wiring as Completion Window Wiring
    participant Speech as Speech-to-Code Service
    participant Audio as Audio Recorder
    participant STT as Moonshine STT
    participant LLM as LLaMA Controller
    participant Insert as Editor Insertion

    Dev->>Editor: Long-press microphone button
    Editor->>Wiring: Trigger voice action
    Wiring->>Speech: Start recording

    Speech->>Audio: Initialize audio recorder
    Audio->>Audio: Record 3 seconds

    Dev->>Audio: "Create a function to validate email"

    Audio-->>Speech: Return audio data
    Speech->>STT: Transcribe audio
    STT-->>Speech: "create a function to validate email"

    Speech->>LLM: Generate code from text
    Note over LLM: Process with context:<br/>- Current file<br/>- Cursor position<br/>- Language (Kotlin)

    LLM-->>Speech: Return generated code
    Speech-->>Wiring: Code ready
    Wiring->>Insert: Insert at cursor
    Insert->>Editor: Update editor content
    Editor->>Dev: Show generated code

    Note over Dev,Editor: Generated:<br/>fun validateEmail(email: String): Boolean {<br/>  return email.matches(Regex("..."))<br/>}
```

---

## Diagram 7: Voice-to-Code Architecture

**Purpose:** System architecture for voice-to-code pipeline

```mermaid
graph TB
    subgraph "Editor Integration"
        EditorMenu[Editor Actions Menu]
        MicButton[Microphone Action]
        CursorPos[Cursor Position]
    end

    subgraph "Wiring Layer"
        Wiring[Editor Completion Window Wiring]
        ActionReg[Actions Registry]
    end

    subgraph "Speech Processing Pipeline"
        SpeechService[Speech-to-Code Service]

        subgraph "Audio Capture"
            Recorder[Audio Recorder]
            PermCheck[Permission Check]
        end

        subgraph "Transcription"
            Moonshine[Moonshine STT Engine]
            AudioPrep[Audio Preprocessing]
        end

        subgraph "Code Generation"
            ContextCollect[Context Collector]
            PromptBuilder[Prompt Builder]
            LLM[LLaMA Generator]
        end
    end

    subgraph "Editor Modification"
        TextInserter[Text Insertion API]
        UndoStack[Undo/Redo Stack]
    end

    EditorMenu --> MicButton
    MicButton --> Wiring
    Wiring --> ActionReg

    Wiring --> SpeechService
    SpeechService --> PermCheck
    PermCheck --> Recorder

    Recorder --> AudioPrep
    AudioPrep --> Moonshine
    Moonshine --> ContextCollect

    ContextCollect --> CursorPos
    ContextCollect --> PromptBuilder
    PromptBuilder --> LLM

    LLM --> TextInserter
    TextInserter --> UndoStack
    UndoStack --> EditorMenu

    style MicButton fill:#9C27B0
    style SpeechService fill:#4CAF50
    style Moonshine fill:#FF9800
    style LLM fill:#2196F3
```

---

## Diagram 8: Smart Completion User Flow

**Purpose:** User interaction flow for AI-powered code completion

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant Editor as IDE Editor
    participant CompWindow as Completion Window
    participant Provider as Smart Completion Provider
    participant Context as Context Extractor
    participant LLM as LLaMA Controller
    participant Display as Completion List

    Dev->>Editor: Type code: "fun parse"
    Editor->>CompWindow: Trigger completion

    CompWindow->>CompWindow: Get LSP completions
    Note over CompWindow: Standard completions:<br/>- parseJson()<br/>- parseInt()

    CompWindow->>Provider: Request AI completions
    Provider->>Context: Extract context

    Context->>Context: Analyze surrounding code
    Note over Context: Context includes:<br/>- Current class<br/>- Imports<br/>- Recent code<br/>- Variable types

    Context-->>Provider: Return context snapshot
    Provider->>LLM: Generate completions

    Note over LLM: Prompt:<br/>"Given context, suggest<br/>completions for 'parse'"

    LLM-->>Provider: Return suggestions
    Note over Provider: AI suggestions:<br/>- parseUserInput(input: String)<br/>- parseJsonResponse(json: String)

    Provider-->>CompWindow: Return AI completions
    CompWindow->>Display: Merge LSP + AI results

    Display->>Dev: Show combined list
    Note over Dev,Display: Results:<br/>💡 LSP: parseJson()<br/>💡 LSP: parseInt()<br/>🤖 AI: parseUserInput()<br/>🤖 AI: parseJsonResponse()

    Dev->>Display: Select AI suggestion
    Display->>Editor: Insert code
    Editor->>Dev: Code inserted with params
```

---

## Diagram 9: Smart Completion Architecture

**Purpose:** Technical architecture of smart completion system

```mermaid
graph TB
    subgraph "Editor Layer"
        Editor[IDE Editor]
        CompWin[Editor Completion Window]
        LSP[LSP Completions]
    end

    subgraph "Smart Completion Module"
        Provider[Smart Completion Provider]
        Bridge[Smart Completion Bridge]

        subgraph "Context Analysis"
            Extractor[Context Extractor]
            Analyzer[Code Analyzer]
            Scope[Scope Detective]
        end

        subgraph "Completion Generation"
            Generator[LLaMA Code Completion]
            Ranker[Relevance Ranker]
            Filter[Duplicate Filter]
        end
    end

    subgraph "AI Services"
        Manager[AI Services Manager]
        LlamaCtrl[LLaMA Controller]
        ModelReg[Model Registry]
    end

    subgraph "Configuration"
        Prefs[Smart Completion Preferences]
        Settings[User Settings]
    end

    Editor --> CompWin
    CompWin --> LSP
    CompWin --> Provider

    Provider --> Bridge
    Bridge --> Manager
    Manager --> LlamaCtrl
    LlamaCtrl --> ModelReg

    Provider --> Extractor
    Extractor --> Analyzer
    Analyzer --> Scope

    Scope --> Generator
    Generator --> Ranker
    Ranker --> Filter
    Filter --> CompWin

    Provider --> Prefs
    Prefs --> Settings

    style Editor fill:#2196F3
    style Provider fill:#4CAF50
    style Generator fill:#F44336
    style LlamaCtrl fill:#9C27B0
```

---

## Diagram 10: Before/After Comparison

**Purpose:** Visual comparison of plugin vs library architecture

```mermaid
graph TB
    subgraph "BEFORE: Plugin Architecture"
        direction TB
        IDE1[IDE Core]
        Plugin1[AI Chat Plugin<br/>APK]

        Plugin1 -.IPC.-> IDE1

        subgraph "Isolated Resources"
            Model1A[LLaMA Model<br/>Copy 1]
            Model1B[LLaMA Model<br/>Copy 2]
            Model1C[LLaMA Model<br/>Copy 3]
        end

        Plugin1 --> Model1A
        Plugin1 --> Model1B
        Plugin1 --> Model1C
    end

    subgraph "AFTER: Library Architecture"
        direction TB
        IDE2[IDE Core]

        subgraph "AI Chat Library"
            Manager[AI Services Manager]
            Chat2[Chat]
            Vector2[Vector]
            Voice2[Voice]
            Smart2[Smart]
        end

        subgraph "Shared Resources"
            ModelShared[LLaMA Model<br/>Shared Instance]
        end

        IDE2 --> Manager
        Manager --> Chat2
        Manager --> Vector2
        Manager --> Voice2
        Manager --> Smart2

        Chat2 --> ModelShared
        Vector2 --> ModelShared
        Voice2 --> ModelShared
        Smart2 --> ModelShared
    end

    style Plugin1 fill:#FF5252
    style Manager fill:#4CAF50
    style ModelShared fill:#2196F3
```

---

## Diagram 11: Cross-Feature Integration Benefits

**Purpose:** Shows synergies between different AI features

```mermaid
graph LR
    subgraph "Synergy Examples"
        direction TB

        Example1[Chat asks about code]
        Example1 --> VectorSearch1[Vector Search finds context]
        VectorSearch1 --> Response1[More accurate chat response]

        Example2[Voice command: 'add logger']
        Example2 --> SmartComp[Smart Completion suggests patterns]
        SmartComp --> Code2[Generated code with best practices]

        Example3[Developer searches for 'auth']
        Example3 --> VectorResults[Vector search returns files]
        VectorResults --> ChatContext[Files auto-added to chat context]
        ChatContext --> Explain[Chat explains implementation]
    end

    style Example1 fill:#2196F3
    style Example2 fill:#9C27B0
    style Example3 fill:#FF9800
```

---

## Diagram 12: Development Timeline

**Purpose:** Gantt chart showing project phases and timeline

```mermaid
gantt
    title Plugin to Library Conversion Timeline
    dateFormat YYYY-MM-DD
    section Foundation
    Phase 0: Smart Completion Wiring           :done, p0, 2026-01-15, 10d
    Phase 1: Token Efficiency                  :done, p1, 2026-01-25, 7d
    section Core Features
    Phase 2: Speech-to-Code Pipeline           :done, p2, 2026-02-01, 14d
    Phase 3: Vector Search Implementation      :done, p3, 2026-02-15, 30d
    Phase 4: Model Optimization                :done, p4, 2026-03-17, 10d
    section Integration
    Plugin Extraction                          :done, extract, 2026-03-27, 20d
    Library Conversion                         :done, convert, 2026-04-16, 15d
    Testing & Documentation                    :done, test, 2026-05-01, 20d
    section Polish
    Bug Fixes & Refinement                     :done, bugs, 2026-05-21, 15d
    Final Integration                          :active, final, 2026-06-05, 12d
```

---

## Diagram 13: Architecture Roadmap

**Purpose:** Evolution from current state to future vision

```mermaid
graph TB
    subgraph "Current State (v1.0)"
        Current[AI Chat Library]
        C1[Chat Service]
        C2[Vector Search]
        C3[Voice-to-Code]
        C4[Smart Completion]

        Current --> C1
        Current --> C2
        Current --> C3
        Current --> C4
    end

    subgraph "Next Release (v1.5)"
        Next[Enhanced AI Services]
        N1[Multi-Model Support]
        N2[Cloud Sync]
        N3[Analytics]
        N4[Plugin Marketplace]

        Next --> N1
        Next --> N2
        Next --> N3
        Next --> N4
    end

    subgraph "Future Vision (v2.0)"
        Future[Autonomous Development Assistant]
        F1[Auto Refactoring]
        F2[Project Analysis]
        F3[Team Collaboration]
        F4[Productivity Metrics]

        Future --> F1
        Future --> F2
        Future --> F3
        Future --> F4
    end

    Current -.Evolves.-> Next
    Next -.Evolves.-> Future

    style Current fill:#4CAF50
    style Next fill:#2196F3
    style Future fill:#9C27B0
```

---

## Diagram 14: Initialization Flow

**Purpose:** Detailed sequence of application startup and AI service initialization

```mermaid
sequenceDiagram
    participant App as Application Start
    participant Loader as CredentialProtectedLoader
    participant Manager as AI Services Manager
    participant Llama as LLaMA Controller
    participant Chat as Chat Service
    participant Vector as Vector Service
    participant Voice as Voice Service
    participant Smart as Smart Completion
    participant UI as UI Components

    App->>Loader: Initialize app
    Loader->>Loader: Load plugins
    Loader->>Manager: Initialize AI Services

    Manager->>Llama: Initialize LLaMA Controller
    Llama->>Llama: Load model metadata
    Llama-->>Manager: Controller ready

    par Parallel Service Initialization
        Manager->>Chat: Initialize with controller
        Manager->>Vector: Initialize with controller
        Manager->>Voice: Initialize with controller
        Manager->>Smart: Initialize with controller
    end

    Chat-->>Manager: Service ready
    Vector-->>Manager: Service ready
    Voice-->>Manager: Service ready
    Smart-->>Manager: Service ready

    Manager-->>Loader: All services initialized
    Loader->>UI: Enable AI features
    UI-->>App: Application ready

    Note over App,UI: Total initialization: <2 seconds<br/>Lazy model loading deferred
```

---

## Diagram 15: Model Lifecycle Management

**Purpose:** State machine showing ML model loading and management

```mermaid
stateDiagram-v2
    [*] --> Uninitialized

    Uninitialized --> MetadataLoaded: App starts
    MetadataLoaded --> ModelLoading: First AI feature used

    ModelLoading --> ModelReady: Load success
    ModelLoading --> ModelError: Load failure

    ModelReady --> Inferencing: Feature request
    Inferencing --> ModelReady: Complete

    ModelReady --> Unloading: Memory pressure
    Unloading --> MetadataLoaded: Unload complete

    ModelError --> RetryLoading: Retry after delay
    RetryLoading --> ModelLoading: Attempt retry

    ModelReady --> [*]: App shutdown

    note right of ModelLoading
        Uses mutex for thread safety
        Only one load attempt at a time
    end note

    note right of Inferencing
        Concurrent inference supported
        Queue management for fairness
    end note
```

---

## Diagram 16: Test Coverage Map

**Purpose:** Visualization of test pyramid and coverage by module

```mermaid
graph TB
    subgraph "Test Pyramid"
        direction TB

        E2E[E2E Tests<br/>1,800 lines<br/>18 tests]
        Integration[Integration Tests<br/>1,500 lines<br/>25 tests]
        Unit[Unit Tests<br/>3,200 lines<br/>120+ tests]

        E2E --> Integration
        Integration --> Unit
    end

    subgraph "Coverage by Module"
        direction TB

        ChatTests[Chat Service: 85%<br/>22 tests]
        VectorTests[Vector Search: 85%<br/>22 tests]
        VoiceTests[Voice-to-Code: 70%<br/>8 tests]
        SmartTests[Smart Completion: 90%<br/>15 tests]
        InfraTests[Infrastructure: 80%<br/>35 tests]
    end

    Unit --> ChatTests
    Unit --> VectorTests
    Unit --> VoiceTests
    Unit --> SmartTests
    Unit --> InfraTests

    style E2E fill:#4CAF50
    style Integration fill:#2196F3
    style Unit fill:#FF9800
```

---

## Diagram 17: Memory Optimization Strategy

**Purpose:** Flow showing memory management and optimization

```mermaid
graph TB
    subgraph "Memory Management"
        direction TB

        Request[Feature Request]
        Check{Model<br/>Loaded?}
        LoadModel[Load Model<br/>200-300MB]
        UseModel[Use Model]
        Monitor[Monitor Memory]
        Pressure{Memory<br/>Pressure?}
        Unload[Unload Model]
        GC[Trigger GC]

        Request --> Check
        Check -->|No| LoadModel
        Check -->|Yes| UseModel
        LoadModel --> UseModel
        UseModel --> Monitor
        Monitor --> Pressure
        Pressure -->|High| Unload
        Pressure -->|Normal| Request
        Unload --> GC
        GC --> Request
    end

    subgraph "Shared Model Benefits"
        Before[Before: 3 Instances<br/>600-900MB]
        After[After: 1 Instance<br/>200-300MB]
        Savings[60% Memory Savings]

        Before -.Optimization.-> After
        After --> Savings
    end

    style LoadModel fill:#FF9800
    style Unload fill:#F44336
    style Savings fill:#4CAF50
```

---

## Usage Notes

### How to Render

1. **GitHub/GitLab:** Diagrams render automatically in markdown preview
2. **Mermaid Live Editor:** Copy-paste into https://mermaid.live
3. **VSCode:** Use "Markdown Preview Mermaid Support" extension
4. **Documentation Sites:** Works with MkDocs, Docusaurus, VuePress
5. **Export:** Use mermaid-cli to generate PNG/SVG/PDF

### Customization

Each diagram uses consistent color coding:
- **Green (#4CAF50):** Primary/success states
- **Blue (#2196F3):** Core services/components
- **Orange (#FF9800):** Search/processing
- **Purple (#9C27B0):** AI/ML components
- **Red (#F44336):** Error/deprecated states

### Diagram Categories

- **Architecture (1, 3, 5, 7, 9, 10):** System structure
- **User Flows (2, 4, 6, 8, 11):** Interaction sequences
- **Process (12, 14, 15, 17):** Workflows and state management
- **Metrics (13, 16):** Progress and quality measurements

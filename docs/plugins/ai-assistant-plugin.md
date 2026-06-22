# AI Assistant Plugin

AI-powered chat assistant and code helpers with local and cloud LLM support.

## Features

### Agent Chat Tab
- Interactive chat interface with AI assistant
- Two backend options: Gemini API (cloud) and Local LLM (on-device)
- Chat history management and sessions
- Context-aware code assistance

### Code Helpers
- **Explain Code**: Get explanations for selected code
- **Generate Code**: Generate code from natural language descriptions

## Installation

1. Ensure ai-core-plugin is installed (for local LLM support)
2. Download ai-assistant-plugin.cgp
3. Settings → Plugin Manager → Install Plugin
4. Select ai-assistant-plugin.cgp
5. Restart app

## Configuration

### Backend Selection

**Gemini API (Cloud)**
- Go to Agent → Settings
- Select "GEMINI" backend
- Enter your Gemini API key
- API key is encrypted and stored securely

**Local LLM (On-Device)**
- Requires ai-core-plugin installed
- Go to Agent → Settings
- Select "LOCAL_LLM" backend
- Model runs on device, no internet required

### Chat Settings
- Temperature: Controls response randomness
- Max tokens: Limits response length
- System prompt: Customize agent behavior

## Dependencies

- **Required**: None (works standalone with Gemini)
- **Optional**: ai-core-plugin (enables local LLM backend)

## Permissions

- `network.access` - For Gemini API communication

## Data Migration

On first launch after upgrading, the plugin automatically migrates:
- Chat history from app storage
- Backend preferences
- Model settings
- API keys

Original files are preserved for safety.

## Troubleshooting

**Agent tab doesn't appear**
- Verify plugin is installed: Settings → Plugin Manager
- Restart app after installation

**LOCAL_LLM option missing**
- Install ai-core-plugin
- Restart app

**"LLM service not available" error**
- Check ai-core-plugin is loaded
- Check logs: `adb logcat | grep "AI Core"`

## Architecture

The plugin integrates with ai-core-plugin's `LlmInferenceService` for local inference:

```
ai-assistant-plugin
├─ Agent UI (ChatFragment, ViewModels)
├─ GeminiRepositoryImpl (cloud backend)
├─ PluginBasedLocalLlmRepository (local backend)
└─ Consumes: LlmInferenceService from ai-core-plugin
```

## Development

See `docs/migration/agent-to-plugin-migration.md` for implementation details.

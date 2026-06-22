# Agent to Plugin Migration Guide

This document describes the migration of Agent functionality from the main app to ai-assistant-plugin.

## Overview

**Before:** Agent tab built into main app, bundled with llama.aar (~50MB)

**After:** Agent tab provided by plugin, llama.cpp in ai-core-plugin

## For Users

### What Changed
- Agent tab moved from app to plugin
- Must install ai-assistant-plugin to use Agent
- Chat history and settings migrate automatically

### Migration Steps
1. Update to latest app version
2. Install ai-assistant-plugin.cgp
3. Install ai-core-plugin.cgp (optional, for local LLM)
4. Restart app
5. Agent tab reappears with all history preserved

## For Developers

### Architecture Changes

**App module cleanup:**
- Removed `app/src/main/java/com/itsaky/androidide/agent/`
- Removed llama-api dependency
- Removed llama AAR files (~50MB)
- App size reduced ~25%

**Plugin module:**
- All Agent code moved to ai-assistant-plugin
- New: `PluginBasedLocalLlmRepository` integrates with ai-core-plugin
- Implements `UIExtension.getEditorTabs()` for tab registration

### Code Migration Map

| From (app) | To (plugin) |
|------------|-------------|
| `agent/fragments/` | `aiassistant/fragments/` |
| `agent/viewmodel/` | `aiassistant/viewmodel/` |
| `agent/repository/` | `aiassistant/repository/` |
| `LocalLlmRepositoryImpl` | `PluginBasedLocalLlmRepository` |
| Built-in tab | `UIExtension.getEditorTabs()` |

### Key Implementation Details

**Service Integration:**
```kotlin
val llmService = pluginContext.services.get(LlmInferenceService::class.java)
if (llmService != null) {
    // Use ai-core-plugin for local inference
}
```

**Data Migration:**
- Chat history: `app/files/chat_sessions/` → `plugin/files/chat_sessions/`
- Settings: `LlamaPrefs` → `AgentSettings`
- Runs automatically on plugin activation

**Graceful Degradation:**
- Plugin works without ai-core (Gemini only)
- LOCAL_LLM option hidden if service unavailable
- Clear error messages guide users

## Implementation Plan

See `docs/superpowers/plans/2026-06-22-agent-to-plugin-migration.md` for detailed step-by-step implementation.

## Testing

See `docs/testing/agent-migration-test-results.md` for test coverage and results.

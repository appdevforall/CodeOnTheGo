Gemma 3 Phase 1 - Baseline and Model Selection

Scope

- Inventory current local LLM pipeline in this repo
- Define device tiers and default Gemma 3 quant choices (no registry; user selects GGUF)
- Capture immediate Phase 1 decisions and TODOs

Current pipeline inventory (as implemented)

- Model loader: LlmInferenceEngine initializes llama controller, loads GGUF from a URI into app
  cache as "local_model.gguf".
- Native bridge: LLamaAndroid wraps llama.cpp and exposes load, context, batch, sampler, and
  completion loop.
- Sampling defaults: LlmInferenceEngine.configureNativeDefaults sets temperature=0.7, top_p=0.9,
  top_k=40 via LLamaAndroid.configureSampling.
- Thread defaults: LlmInferenceEngine.configureNativeDefaults sets n_threads and n_threads_batch
  to (cores-2) clamped to 2..6.
- Inference flow: LlmInferenceEngine.runInference clears KV cache and runs a single completion with
  stop strings.
- Tooling: LocalAgenticRunner builds simplified prompts and parses tool calls; tool responses are
  formatted for UI.

Device tiers and recommended Gemma 3 quants

- 4 GB RAM (budget): Gemma 3 2.7B IQ3_XXS
- 6-8 GB RAM (mid): Gemma 3 9B IQ3_M
- 12+ GB RAM (high): Gemma 3 9B IQ4_XS

Initial defaults to confirm

- Context size (n_ctx):
    - 4 GB: 1024
    - 6-8 GB: 2048-4096
    - 12+ GB: 4096
- Output budget: reserve 25-50% of n_ctx for generation
- Threading: physical cores (not logical); clamp to 2..6 for mid devices

Phase 1 decisions needed (owner: product/engineering)

1) Should we keep manual model selection only (no registry), or add optional recommendations?
2) Do we want optional integrity checks only when user provides a hash?
3) Download path + integrity: allow external storage or cache only?

Phase 1 TODOs (engineering)

- Use filename-based model family detection (Gemma 3 vs Gemma 2 vs Llama).
- Keep manual model selection; skip registry.
- Add optional integrity check hook when user provides a hash.

Progress implemented (Jan 28, 2026)

1) Context size policy (n_ctx) by device tier
    - Native llama.cpp now supports configurable n_ctx (default 4096).
    - LlmInferenceEngine auto-sets n_ctx based on total RAM:
        - <= 4.5 GB: 1024
        - <= 8.5 GB: 2048
        - <= 12.5 GB: 3072
        - > 12.5 GB: 4096

2) Output budget (max tokens) tied to n_ctx
    - LLamaAndroid exposes configureMaxTokens.
    - Engine sets maxTokens = 25% of n_ctx (clamped 128..512).

3) Token budgeting + history trimming (prompt input)
    - Simplified local prompt uses token budgeting:
        - Input budget = ~70% of n_ctx.
        - Uses real tokenizer token counts when available; falls back to heuristic.
        - Drops oldest history lines until budget fits.

4) Optional SHA-256 integrity check (no registry)
    - AI Settings UI includes optional SHA-256 input.
    - If provided, model load verifies hash before loading.
    - No hash provided => no verification (manual selection stays).

5) KV-cache reuse (speed-up for multi-turn)
    - Native layer keeps cached tokens and reuses KV cache when the next prompt
      extends the previous token sequence (prefix match).
    - If prompt diverges, it clears KV cache and decodes from scratch.
    - Simplified workflow avoids clearing KV cache when history exists.

Files touched (implementation reference)

- llama-impl/src/main/cpp/llama-android.cpp
- llama-impl/src/main/java/android/llama/cpp/LLamaAndroid.kt
- llama-api/src/main/java/com/itsaky/androidide/llamacpp/api/ILlamaController.kt
- agent/src/main/java/com/itsaky/androidide/agent/repository/LlmInferenceEngine.kt
- agent/src/main/java/com/itsaky/androidide/agent/repository/LocalAgenticRunner.kt
- agent/src/main/java/com/itsaky/androidide/agent/repository/Constants.kt
- agent/src/main/java/com/itsaky/androidide/agent/viewmodel/AiSettingsViewModel.kt
- agent/src/main/java/com/itsaky/androidide/agent/viewmodel/ChatViewModel.kt
- agent/src/main/java/com/itsaky/androidide/agent/fragments/AiSettingsFragment.kt
- agent/src/main/res/layout/layout_settings_local_llm.xml

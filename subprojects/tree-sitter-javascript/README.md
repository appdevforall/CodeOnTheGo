# tree-sitter-javascript

The tree-sitter JavaScript grammar for the editor (ADFA-7084), built from source for the `v7` (`armeabi-v7a`) and `v8` (`arm64-v8a`) flavors.

The other grammars (Java, Kotlin, JSON, XML, log) come from Maven Central as `com.itsaky.androidide.treesitter:tree-sitter-*`, built by android-tree-sitter (`itsaky/android-tree-sitter` on GitHub). It publishes no JavaScript grammar, so this module builds one the same shape: `TSLanguageJavascript.getInstance()` returns a `TSLanguage` for the same runtime (`libs.androidide.ts`).

## Contents

- `grammar/`: tree-sitter-javascript (`tree-sitter/tree-sitter-javascript` on GitHub) v0.23.1 (commit `3a837b6`, MIT, see `grammar/LICENSE`): its generated `src/parser.c`, `src/scanner.c` and `src/tree_sitter/*.h`, unmodified.
- `src/main/cpp/`: the CMake build and the JNI binding (`JNI_OnLoad` registers `Native.getInstance()`).
- `src/main/java/`: `TSLanguageJavascript`.

The editor's queries for JavaScript are in `editor/src/main/assets/editor/treesitter/js/`.

## Updating the grammar

The runtime, android-tree-sitter 4.3.2, bundles tree-sitter 0.22.1, which loads grammars of ABI version 13 or 14. v0.23.1 is the newest tree-sitter-javascript release at ABI 14; v0.25.0 is ABI 15 and needs a newer runtime. `grep LANGUAGE_VERSION grammar/src/parser.c` shows a release's ABI.

To update, copy the same files from the new release's tag into `grammar/`, update the version here, and check that the queries in `editor/.../treesitter/js/` still compile: the instrumented `JavaScriptLanguageTest` in `:editor` compiles each of them.

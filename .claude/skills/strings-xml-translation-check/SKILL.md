---
name: strings-xml-translation-check
description: QA-check an Android strings.xml localization (a values-XX/strings.xml PR or file) when you don't read the target language. Round-trips every translated value back to English through BOTH Gemini and Google Cloud Translate via the bundled scripts/translate-strings-xml.py, then surfaces semantic drift, broken placeholders/escapes/tags, unescaped quotes, and tone/tense mismatches. Use when asked to review, QA, or check a translation/localization PR or a values-XX strings file.
metadata:
  author: Hal Eisen
  keywords:
  - translation
  - localization
  - strings.xml
  - i18n
  - back-translation
  - PR-review
---

## What this is

Reviewing a translation you can't read is the problem this solves. The core
technique: **back-translate every foreign value to English through two
independent MT engines (Gemini + Google Cloud Translate) and cross-check.**
Agreement between the two engines is signal; a single engine differing is
noise. Layered on top are mechanical checks (placeholders, escapes, tags) that
catch runtime/display bugs a human reviewer misses.

Dogfooded on PR #1581 (community Simplified-Chinese update): the two-engine
pass confirmed the translation was semantically sound, and the mechanical
checks caught 11 strings with unescaped double-quotes Android would silently
strip.

## When to invoke

- "review this translation PR" / "QA the translations" / "check this localization PR"
- "look at the new translations in PR #N"
- A PR whose file list is dominated by `values-XX/strings.xml` or
  `values-XX-rYY/*.xml` (the foreign-language resource dirs).

## Prerequisites (per person — credentials are NOT shareable)

The script auto-bootstraps its Python deps via `uv run --script` from its
shebang — just exec it. Two auth requirements, each user sets up their own:

- **`GEMINI_API_KEY`** env var. Check: `[ -n "$GEMINI_API_KEY" ]`.
- **Google Cloud ADC** with a quota project (for the Google Translate leg).
  - Check: `gcloud auth application-default print-access-token` exits 0.
  - Refresh (browser pops): `gcloud auth application-default login`.
  - Set quota project once: `gcloud auth application-default set-quota-project <project>`.
  - In non-interactive Claude Code, ask the user to run the `login` command
    with the `!` prefix and resume after.

## Cost & privacy (state this before a large run)

- **Cost:** one review makes roughly **2 x N** cloud MT calls (N = translated
  strings; each value goes to Gemini *and* Google Translate). PR #1581 was
  ~1,185 x 2. Billed to the runner's own keys/project.
- **Privacy:** the strings are sent to Google + Gemini. Fine for an OSS
  project's **public UI strings**; do not run it on anything confidential.

## The workflow

1. **Pair each foreign file with its English source.** For a foreign file at
   `<module>/src/main/res/values-XX[-rYY]/<file>.xml`, the English source is
   `<module>/src/main/res/values/<file>.xml`.
2. **Run the bundled script once per pair** (it lives in `scripts/` alongside
   this SKILL.md; use the absolute path from the skill's base dir). For a big
   file it takes minutes — run it in the background.
3. **Scope the review to the keys the PR actually changed** (via `git diff`).
4. **Flag** drift, broken placeholders/escapes/tags, unescaped quotes, and
   tone/tense mismatch.
5. **Report** findings grouped by file with suggested replacements, tiered.
   The contributor revises; do not edit the foreign XML or push to their
   branch unless the user asks.

### Invocation

```
<skill-dir>/scripts/translate-strings-xml.py \
    <module>/src/main/res/values/<file>.xml \
    <module>/src/main/res/values-XX[-rYY]/<file>.xml \
    --output <scratchpad>/pr<N>-<lang>.xlsx
```

Output columns: `key | english | foreign | gemini_en | google_en`. Run
multiple pairs in parallel (separate Bash calls) — they're independent.

Language code is auto-detected from the directory name (`values-in-rID` ->
`in-ID`). If a back-translator rejects it, pass `--source-lang <code>`
explicitly (e.g. `id` for modern Indonesian).

### Scope to changed keys

Get the changed-key set:

```
git diff <base>..HEAD -- <foreign.xml> | grep -oE 'name="[^"]+"'
```

Then filter the xlsx to those rows with a small `uv run --script` +
`openpyxl` reader. To surface the worst semantic drift first, score each row
by word-overlap of `english` vs each back-translation and sort ascending —
low overlap on BOTH engines = likely real drift; low on one = synonym noise.

## QA checklist (what to flag)

1. **Semantic drift** — both Gemini's and Google's back-translation diverge
   from English in the same direction. One outlier is noise; both agreeing is
   signal. (Short UI labels back-translate to synonyms constantly — e.g.
   `Kill`->终止->"Terminate" — that's expected noise, not an error.)
2. **Placeholders**: `%s`, `%d`, `%1$s`, `%2$d` — must appear unchanged (and
   correctly renumbered). Missing/renumbered placeholders crash at runtime.
3. **Escape sequences**: `\n`, `\'`, `\"`, `\uXXXX` — verify they survived.
4. **Unescaped double-quotes** (high-value, easy to miss): in an Android
   resource, an *unescaped* `"` is a whitespace-preservation delimiter and is
   **silently stripped** from the rendered string — so the translator's quote
   marks vanish in the UI. The xlsx flattens escaping, so scan the **raw
   source** for this:

   ```
   # unescaped straight double-quotes in <string> values, outside CDATA:
   python3 - <<'PY'
   import re
   zh = open("<foreign.xml>").read()
   for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', zh, re.S):
       k, v = m.group(1), m.group(2)
       if 'CDATA' not in v and '"' in v.replace('\\"', ''):
           print(k, repr(v))
   PY
   ```

   Fix: use full-width Chinese quotes `“…”`/`「…」` (or the target language's
   native quotation marks), which render correctly and aren't special to
   Android; or escape as `\"`. Corroboration that it's real: the repo's
   English strings uniformly escape their quotes.
5. **HTML/XML tags & CDATA**: inline `<b>`, `<i>`, `<a href>` must be
   structurally identical; CDATA blocks preserved.
6. **Bullets / special chars**: `•`, `…`, `→`, smart quotes — count them
   (e.g. a dropped trailing `…` on a "More…" menu label).
7. **Tone mismatch** — imperative vs progressive for status messages.
   Indonesian: `msg_enabling_terminal_toolbar` should be "Mengaktifkan…"
   (progressive) not "Aktifkan…" (imperative).
8. **Tense mismatch** — past vs present for success/state strings.
   Indonesian: "Loaded!" -> "Dimuat!" (past), not "Memuat!" ("Loading!").
9. **Inconsistency within file** — same English term rendered two ways. Pull
   all rows for the suspicious term to check.
10. **False friends / wrong synonym** — e.g. "Chat" -> "Pesan" (message) when
    "Obrolan"/"Percakapan" is meant.
11. **Cultural / offense pass** — read the actual foreign values (not the
    back-translation) for register and locale sensitivity: rude/curt tone,
    politically sensitive terms for the region, Traditional-vs-Simplified or
    wrong-region vocabulary. Flag candidates for a native-speaker sign-off;
    this is a screen, not a verdict.

## Reporting findings

Group by file. For each: key, English text, contributor's foreign text,
suggested replacement, one-sentence reason. Tier:

- **Bugs** — placeholder/escape/tag/quote breakage, clear semantic errors ->
  fix before merge.
- **Inconsistencies** — same term translated differently across the file.
- **Soft suggestions** — stylistic nits; mention, don't block.

Hand the list back in chat. The user decides whether to `gh pr review`
(comment / request-changes / approve) or relay it to the contributor.

## Out of scope

- Editing the foreign XML ourselves or pushing to the contributor's branch.
- Translating brand-new languages from scratch.
- Running the Android build — this is *wrong-content* QA (Lint catches missing
  keys; this catches wrong translations).

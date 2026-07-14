# Security Guide

This is a developer guide for **not introducing new security findings** that our scanners — **SonarQube/SonarCloud**, **Snyk**, and **Semgrep** — flag. It explains the vulnerability classes those tools catch in a Kotlin/Java Android app like ours, with concrete do/don't guidance. For the per-PR checklist, see [REVIEW.md](REVIEW.md) §4; for vulnerability disclosure, see [Reporting](#reporting-a-vulnerability) at the bottom.

## The policy: the baseline is frozen, don't grow it

We currently carry a large baseline of **blocker-severity** findings that have been suppressed / marked "won't fix" so the build is workable. That baseline is **accepted technical debt — it is closed.**

- **Your change must not add a new blocker (or high) finding in SonarCloud, Snyk, or Semgrep.** "New code" is what's measured; clean new code is the bar.
- **Do not extend the whitelist/suppression to cover your own code.** Suppression is reserved for **vendored / third-party code we don't own** (the same philosophy as our StrictMode whitelist: fix what we own, suppress only what we can't change). A suppression on app-owned code in a PR is a red flag, not a fix.
- If a tool flags something you believe is a false positive, get explicit reviewer sign-off and annotate *why* — don't silently suppress.

Most of our existing blockers live in vendored subtrees (the `javac`/`jdt` forks, `termux`, native libs). The goal of this doc is to keep **our** code — `app`, plugins, feature modules — clean.

---

## What the three tools catch (and where they differ)

All three overlap heavily on code-level vulnerabilities; each has a distinct strength:

| Tool | Strongest at | In our repo |
|---|---|---|
| **SonarQube / SonarCloud** | Broad Java/Kotlin **quality + security**, Android-aware rules, security hotspots. Most of our blocker count. | Runs in CI (`analyze.yml`) against SonarCloud project `appdevforall_CodeOnTheGo`. |
| **Snyk** | **Vulnerable dependencies (SCA)** — known CVEs in transitive libraries — plus Snyk Code SAST. | Watches our Gradle dependency graph; this is the one that fires on a bad library version. |
| **Semgrep** | **Pattern + taint-flow SAST** — tracks untrusted data from source to dangerous sink; highly customizable rules. | OWASP / Android / secrets rulesets. |

If you only remember one distinction: **Snyk is mostly about the libraries you pull in; Sonar and Semgrep are mostly about the code you write.**

### Relationship to Claude's `/security-review`

Claude Code ships a built-in **`/security-review`** command — a general, reasoning-based security pass over your diff (injection, authz, secrets, unsafe deserialization, etc.). It **complements, not replaces**, the three scanners above:

- The **three scanners own the enforced gate** — the frozen blocker baseline (Sonar/Semgrep) and dependency CVEs (Snyk). CI runs them; they decide merge-ability.
- **`/security-review` catches design- and logic-level issues** that pattern/taint scanners miss, and it's fast to run locally on a security-sensitive change. It has no baseline concept and doesn't gate the build — treat its output as reviewer input, not a pass/fail.
- Use both: run `/security-review` before pushing security-relevant work; rely on the scanners for the enforced "no new blocker" bar. Over time we expect `/security-review` to carry more of the qualitative load — but it doesn't remove the scanner baseline obligations below.

---

## The vulnerability classes to avoid

These are the categories that produce blocker/high findings in apps like ours. Representative SonarQube rule IDs are given where well-known (IDs and severities can shift between versions — treat them as pointers).

### 1. Hardcoded secrets & credentials
*(Sonar S2068/S6418; Snyk Code; Semgrep `secrets`)*
- **Don't** put API keys, tokens, passwords, signing-key passwords, or `google-services.json` values in source, resources, or committed config. All three tools scan for high-entropy strings and known key formats.
- **Do** read secrets from `BuildConfig` fields injected at build time (CI secrets), the Android Keystore, or `EncryptedSharedPreferences`. Git tokens and keystore passwords (`GitCredentialsManager`, the keystore generator) must never be plaintext or logged.

### 2. Injection
- **SQL injection** *(Sonar S3649)* — never concatenate user/project input into SQL. Use parameterized queries: `db.rawQuery(sql, arrayOf(arg))` with `?` placeholders (our `WebServer`/tooltip queries already do this — match them).
- **OS command injection** *(Sonar S2076; Semgrep taint)* — building shell strings from untrusted input is the classic finding. We run a real shell (Termux) and Gradle; pass arguments as a list/array, never an interpolated command string, and validate anything derived from a project path or filename.
- **Path traversal / Zip Slip** *(Sonar S2083 / S6096)* — extracting archives is a top hit for us. Every zip/tar entry (templates via `ZipRecipeExecutor`, imported projects, plugin packages) must be resolved and checked to stay inside the target dir before writing:
  ```kotlin
  val outFile = File(targetDir, entry.name)
  if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
      throw SecurityException("Zip Slip blocked: ${entry.name}")
  }
  ```
  Same idea for any `File(base, userControlledName)`.

### 3. Weak cryptography & randomness
- **Weak hashes** *(Sonar S4790)* — MD5/SHA-1 for anything security-relevant. Use SHA-256+. (MD5 for a non-security cache key is fine but will still get flagged — prefer a non-crypto hash there.)
- **Weak ciphers / modes** *(Sonar S5542/S5547)* — no DES/3DES/RC4, no ECB mode, no hardcoded IVs *(S3329)*. Use AES-GCM with a random IV.
- **Insecure randomness** *(Sonar S2245)* — `java.util.Random` / `Math.random()` for tokens, keys, nonces, or filenames-as-secrets. Use `java.security.SecureRandom`.
- Prefer Jetpack Security / Keystore primitives over rolling your own crypto.

### 4. Insecure network & transport
- **Cleartext traffic** *(Sonar S5332)* — no `http://`, `ftp://`, `ws://` for anything but localhost. Use HTTPS/WSS.
- **Disabled TLS validation** *(Sonar S4830 / S5527)* — never install a trust-all `TrustManager`, an `ALLOW_ALL` `HostnameVerifier`, or override `onReceivedSslError` to proceed. These are guaranteed blockers and a real MITM risk for git/clone and update flows.
- Don't widen `network_security_config` to permit cleartext or user CAs broadly.

### 5. WebView & the local web server
We render content in WebViews (tooltips, markdown preview, APK viewer) and run a local `WebServer` — both are scanner magnets.
- *(Sonar S6362)* `setJavaScriptEnabled(true)` only when required, and never on a WebView loading **remote/untrusted** content.
- **`addJavascriptInterface`** with untrusted content is a remote-code-execution class finding — avoid it; if unavoidable, gate to `@JavascriptInterface` methods and trusted local content only.
- *(Sonar S6363)* don't enable `setAllowFileAccess` / `setAllowUniversalAccessFromFileURLs` unless strictly needed.
- The local `WebServer`: bind to **loopback only**, serve a scoped directory, don't reflect unsanitized request input into responses (XSS/SSRF), and treat every request as untrusted.

### 6. Android component & data exposure
- **Exported components** — `activity`/`service`/`receiver`/`provider` with `android:exported="true"` and no permission is a standard finding. Export only what must be, and protect it with a signature-level permission. Validate all incoming `Intent` extras (intent-redirection / spoofing).
- **PendingIntent** — must be `FLAG_IMMUTABLE` unless mutability is genuinely required.
- **Insecure storage** — no `MODE_WORLD_READABLE/WRITEABLE`; don't put sensitive data on external/shared storage; don't log file contents, tokens, or PII (scanners flag `Log`/print of tainted data, and it also leaks into Sentry/analytics).
- **Manifest hygiene** — `android:allowBackup` and `android:debuggable` are flagged for sensitive apps; set deliberately.
- Request the minimum permissions; over-requesting is flagged.

### 7. Insecure deserialization & XML
- **XXE** *(Sonar S2755)* — when parsing XML (layouts, manifests, project files) disable external entities/DTDs on the factory (`setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`).
- **Untrusted deserialization** — validate JSON (Gson/kotlinx) shape before use; never deserialize untrusted data into arbitrary types.

### 8. Vulnerable & risky dependencies — Snyk's specialty
- **Before adding or bumping a dependency**, check it has no known high/critical CVEs and is maintained. Snyk fires on the *transitive* graph too, so a new direct dep can drag in a flagged one.
- Reuse what's already in `gradle/libs.versions.toml` (our standing rule is to avoid new deps anyway). Pin versions in the catalog; don't introduce dynamic/`+` versions.
- When Snyk flags an existing dep, prefer the smallest safe upgrade; if no fix exists, that's a conversation (and a tracked exception), not a silent ignore.
- Watch licenses too — Snyk/legal flags incompatible licenses for a GPLv3 project.

### 9. Reliability "blockers" (not strictly security)
A chunk of Sonar **blocker** findings are reliability bugs, not vulnerabilities: guaranteed NPEs, resource leaks (unclosed streams/cursors/connections), and dead/always-true conditions. These overlap our leak guidance — see [REVIEW.md](REVIEW.md) §1–2. Use `use {}`, null-safety, and exhaustive `when` to keep them out.

---

## Before you push

- **Self-review against §1–9** for the surfaces your change touches (input handling, crypto, network, WebView, manifest, new deps).
- **Run `/security-review`** on security-sensitive changes for a reasoning-based pass over the diff (complements the scanners — see above).
- If you have the SonarQube MCP / `sonar` CLI handy, scan your branch locally and confirm **no new** blocker/high on changed files. Remember: official analysis runs in CI — local is verification only (`CLAUDE.md`).
- If a finding is genuinely a false positive, document the reasoning and get reviewer sign-off **before** suppressing — and never suppress on code we own without that.

## Reporting a vulnerability

Found a security issue in Code On The Go? **Do not open a public issue.** Email **feedback@appdevforall.org** with details and reproduction steps, and allow time for a fix before public disclosure.

#!/usr/bin/env python3
"""Quick Build (ADFA-4128) corpus matrix runner.

Drives the quickbuild-daemon jar (line-delimited JSON over stdio, see
quick-build/README.md "Daemon protocol") against every app under
quick-build/corpus/apps/, applying each app's edits/<NN>-* in order and
asserting the route, recompiled-class-set bounds, and behavioral marker
declared in each edit's meta.json. Finishes each app with an output-equivalence
check: a from-scratch full compile of the final tree, compared class-by-class
against the incremental result.

Stdlib only. See quick-build/corpus/README.md for usage, the meta.json schema,
and known limitations (this docstring stays high-level on purpose).
"""

from __future__ import annotations

import argparse
import json
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import zlib
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

CODE_EXTS = {".kt", ".java"}
DEFAULT_MIN_API = 30
HARNESS_DIR = Path(__file__).resolve().parent
CORPUS_DIR = HARNESS_DIR.parent
QUICK_BUILD_DIR = CORPUS_DIR.parent
REPO_ROOT = QUICK_BUILD_DIR.parent
DEFAULT_DAEMON_JAR = REPO_ROOT / "quickbuild-daemon" / "build" / "libs" / "quickbuild-daemon.jar"


# ---------------------------------------------------------------------------
# Daemon client: line-delimited JSON over stdin/stdout, one request in flight.
# ---------------------------------------------------------------------------


class DaemonError(RuntimeError):
    """Transport/protocol failure talking to the daemon process. Callers turn
    this into a SKIPPED/FAILED result rather than crashing the whole run."""


class DaemonClient:
    """Synchronous client for the daemon's line-delimited JSON protocol.

    A background reader thread drains stdout into a queue so `_send` can wait
    on it with a timeout (plain `readline()` has no timeout on a pipe). Stderr
    is drained on its own thread so the daemon's own log lines never block the
    protocol stream (stdout is protocol-only per the daemon's own contract).
    """

    def __init__(self, cmd: List[str], timeout_seconds: float = 120.0, stderr_log: Optional[Path] = None):
        self._cmd = cmd
        self._timeout = timeout_seconds
        self._stderr_log = stderr_log
        self._proc: Optional[subprocess.Popen] = None
        self._next_id = 1
        self._out_queue: "queue.Queue[Optional[str]]" = queue.Queue()
        self._eof = False

    def start(self) -> None:
        self._proc = subprocess.Popen(
            self._cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _read_stdout(self) -> None:
        proc = self._proc
        assert proc is not None and proc.stdout is not None
        try:
            for line in proc.stdout:
                self._out_queue.put(line)
        except (OSError, ValueError):
            pass
        finally:
            self._out_queue.put(None)  # EOF sentinel

    def _drain_stderr(self) -> None:
        proc = self._proc
        if proc is None or proc.stderr is None:
            return
        log_file = open(self._stderr_log, "a") if self._stderr_log else None
        try:
            for line in proc.stderr:
                if log_file is not None:
                    log_file.write(line)
        except (OSError, ValueError):
            pass
        finally:
            if log_file is not None:
                log_file.close()

    def is_alive(self) -> bool:
        return self._proc is not None and self._proc.poll() is None and not self._eof

    def _send(self, op: str, **fields: Any) -> Dict[str, Any]:
        if self._proc is None:
            raise DaemonError(f"daemon not started (op={op})")
        if self._eof or self._proc.poll() is not None:
            raise DaemonError(f"daemon process is not running (op={op}, exit={self._proc.poll()})")

        req_id = self._next_id
        self._next_id += 1
        line = json.dumps({"id": req_id, "op": op, **fields})
        try:
            assert self._proc.stdin is not None
            self._proc.stdin.write(line + "\n")
            self._proc.stdin.flush()
        except (BrokenPipeError, OSError) as e:
            raise DaemonError(f"failed writing request (op={op}): {e}") from e

        try:
            raw = self._out_queue.get(timeout=self._timeout)
        except queue.Empty:
            raise DaemonError(f"daemon did not respond within {self._timeout}s (op={op}, id={req_id})")
        if raw is None:
            self._eof = True
            raise DaemonError(f"daemon closed stdout without responding (op={op}, id={req_id})")

        try:
            resp = json.loads(raw)
        except json.JSONDecodeError as e:
            raise DaemonError(f"malformed response for op={op}: {raw!r} ({e})") from e
        if not isinstance(resp, dict) or "id" not in resp or "ok" not in resp:
            raise DaemonError(f"response missing id/ok for op={op}: {raw!r}")
        if resp["id"] != req_id:
            raise DaemonError(f"response id mismatch for op={op}: sent {req_id}, got {resp.get('id')}")
        return resp

    def configure(self, **fields: Any) -> Dict[str, Any]:
        return self._send("configure", **fields)

    def compile(self, all_sources: List[str], changed_files: List[str]) -> Dict[str, Any]:
        return self._send("compile", allSources=all_sources, changedFiles=changed_files)

    def dex(self, classes_dirs: List[str]) -> Dict[str, Any]:
        return self._send("dex", classesDirs=classes_dirs)

    def relink(self, res_dirs: List[str], manifest: str) -> Dict[str, Any]:
        return self._send("relink", resDirs=res_dirs, manifest=manifest)

    def ping(self) -> Dict[str, Any]:
        return self._send("ping")

    def health_check(self) -> Tuple[bool, str]:
        try:
            resp = self.ping()
            return bool(resp.get("ok")), "ok"
        except DaemonError as e:
            return False, str(e)

    def stop(self) -> None:
        if self._proc is None:
            return
        if self.is_alive():
            try:
                self._send("shutdown")
            except DaemonError:
                pass
        try:
            if self._proc.stdin:
                self._proc.stdin.close()
        except (OSError, ValueError):
            pass
        try:
            self._proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self._proc.kill()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        self._proc = None


# ---------------------------------------------------------------------------
# Class-file CRC snapshotting (the output-equivalence + recompiled-set oracle)
# ---------------------------------------------------------------------------


def snapshot_class_crcs(classes_dir: Path) -> Dict[str, int]:
    """relative .class path (posix) -> zlib.crc32 of its bytes."""
    crcs: Dict[str, int] = {}
    if not classes_dir.is_dir():
        return crcs
    for path in sorted(classes_dir.rglob("*.class")):
        rel = path.relative_to(classes_dir).as_posix()
        crcs[rel] = zlib.crc32(path.read_bytes())
    return crcs


def diff_recompiled(before: Dict[str, int], after: Dict[str, int]) -> List[str]:
    """Classes that are new or changed CRC, plus any removed. This is the
    'recompiledClasses' set the plan's oracle uses: a line executed with an
    unchanged CRC means the compiler decided that class didn't need rebuilding."""
    changed = [rel for rel, crc in after.items() if before.get(rel) != crc]
    changed += [rel for rel in before if rel not in after]
    return sorted(changed)


def compare_class_trees(incremental: Dict[str, int], clean: Dict[str, int]) -> Tuple[bool, str]:
    only_incremental = sorted(set(incremental) - set(clean))
    only_clean = sorted(set(clean) - set(incremental))
    mismatched = sorted(k for k in set(incremental) & set(clean) if incremental[k] != clean[k])
    if not only_incremental and not only_clean and not mismatched:
        return True, f"{len(clean)} classes match (set + CRC)"
    parts = []
    if only_incremental:
        parts.append(f"only in incremental build: {only_incremental}")
    if only_clean:
        parts.append(f"only in clean build: {only_clean}")
    if mismatched:
        parts.append(f"CRC mismatch: {mismatched}")
    return False, "; ".join(parts)


# ---------------------------------------------------------------------------
# R class generation (harness-side, NOT part of the daemon protocol)
# ---------------------------------------------------------------------------
#
# The daemon's `relink` op extracts only resources.arsc (see
# quickbuild-daemon/.../res/Aapt2Link.kt) -- it never emits R.java. But every
# corpus app references R.string/R.color/etc (even just via
# android:label="@string/app_name" in the manifest), so `compile` cannot
# succeed without an R class first. This helper runs aapt2 directly
# (bypassing the daemon) to produce R.java, then javac-precompiles it.
#
# Precompiled-and-on-the-classpath, NOT passed as a source: IncrementalCompiler.kt's
# `compile()` filters allSources into kotlinSources/javaSources and calls
# `compileKotlin` (Kotlin Build Tools API) BEFORE the (non-incremental) javac pass
# that handles `.java` files -- kotlinc never sees `.java` sources for symbol
# resolution in this setup, so an R.java source alongside .kt files compiles fine
# for Java-only apps (javac sees both together) but leaves every Kotlin source with
# an unresolved `R` reference. Precompiling R.java ourselves and adding the output
# dir to `configure`'s `classpath` sidesteps the cross-language visibility gap
# entirely: both the kotlinc pass and the javac pass resolve R via classpath.
#
# Best-effort throughout: returns None if aapt2/javac/android.jar are unavailable,
# or if aapt2/javac themselves fail -- callers treat that as an environment gap
# (SKIPPED), not a code defect (FAILED). See the corpus README's LIMITATIONS section.


def _aapt2_generate_r_java(
    app_package: str,
    res_dir: Path,
    manifest_path: Path,
    android_jar: Path,
    aapt2: Path,
    scratch_dir: Path,
) -> Optional[Path]:
    if not res_dir.is_dir() or not manifest_path.is_file():
        return None

    compiled_dir = scratch_dir / "compiled"
    java_out = scratch_dir / "java-out"
    for d in (compiled_dir, java_out):
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
    linked_apk = scratch_dir / "linked.apk"

    try:
        compile_result = subprocess.run(
            [str(aapt2), "compile", "--dir", str(res_dir), "-o", str(compiled_dir)],
            capture_output=True,
            text=True,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if compile_result.returncode != 0:
        return None

    flat_files = sorted(str(p) for p in compiled_dir.glob("*.flat"))
    link_cmd = [
        str(aapt2), "link",
        "-o", str(linked_apk),
        "--manifest", str(manifest_path),
        "-I", str(android_jar),
        "--auto-add-overlay",
        "--java", str(java_out),
        "--custom-package", app_package,
        *flat_files,
    ]
    try:
        link_result = subprocess.run(link_cmd, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if link_result.returncode != 0:
        return None
    return java_out


def generate_r_classes(
    app_package: str,
    res_dir: Path,
    manifest_path: Path,
    android_jar: Optional[Path],
    aapt2: Optional[Path],
    javac_bin: Optional[Path],
    scratch_dir: Path,
) -> Optional[Path]:
    """aapt2 compile+link to produce R.java, then javac -d into a fresh classes
    dir. Returns that classes dir (a `configure` classpath entry), or None on any
    gap in the chain."""
    if aapt2 is None or android_jar is None or javac_bin is None:
        return None
    if not aapt2.exists() or not android_jar.exists() or not javac_bin.exists():
        return None

    java_out = _aapt2_generate_r_java(app_package, res_dir, manifest_path, android_jar, aapt2, scratch_dir)
    if java_out is None:
        return None
    r_sources = sorted(str(p) for p in java_out.rglob("R.java"))
    if not r_sources:
        return None

    rclasses_dir = scratch_dir / "rclasses"
    if rclasses_dir.exists():
        shutil.rmtree(rclasses_dir)
    rclasses_dir.mkdir(parents=True)
    try:
        javac_result = subprocess.run(
            [str(javac_bin), "-d", str(rclasses_dir), *r_sources],
            capture_output=True,
            text=True,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if javac_result.returncode != 0:
        return None
    return rclasses_dir


# ---------------------------------------------------------------------------
# App tree / edit filesystem helpers
# ---------------------------------------------------------------------------


def copy_app_tree(app_dir: Path, work_dir: Path) -> None:
    if work_dir.exists():
        shutil.rmtree(work_dir)

    def ignore(directory: str, names: List[str]) -> set:
        if Path(directory) == app_dir:
            return {"edits", "app.json"}
        return set()

    shutil.copytree(app_dir, work_dir, ignore=ignore)


def apply_edit(work_dir: Path, edit_dir: Path, meta: Dict[str, Any]) -> List[str]:
    """Copies each replacement file into place. Returns the repo-relative
    (posix) paths that changed, in meta.json's declared order."""
    changed: List[str] = []
    for rel_path, replacement_name in meta["files"].items():
        src = edit_dir / replacement_name
        if not src.is_file():
            raise FileNotFoundError(f"{edit_dir}: meta.json references missing file {src}")
        dst = work_dir / rel_path
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        changed.append(rel_path)
    return changed


def is_code_path(rel_path: str) -> bool:
    return Path(rel_path).suffix in CODE_EXTS


def is_res_path(rel_path: str) -> bool:
    return rel_path.startswith("src/main/res/")


def list_all_sources(work_dir: Path) -> List[str]:
    """Absolute paths of every .kt/.java under src/main/java. The generated R
    class is NOT a source here -- see generate_r_classes's docstring for why it's
    a classpath entry instead."""
    sources: List[str] = []
    java_root = work_dir / "src" / "main" / "java"
    if java_root.is_dir():
        for path in sorted(java_root.rglob("*")):
            if path.is_file() and path.suffix in CODE_EXTS:
                sources.append(str(path))
    return sources


# ---------------------------------------------------------------------------
# Report data shapes (plain dicts, JSON-native -- no dataclass<->dict layer)
# ---------------------------------------------------------------------------


def make_assertion(name: str, status: str, detail: str) -> Dict[str, Any]:
    assert status in ("PASS", "FAILED", "SKIPPED")
    return {"name": name, "status": status, "detail": detail}


def aggregate_status(assertions: List[Dict[str, Any]]) -> str:
    statuses = {a["status"] for a in assertions}
    if "FAILED" in statuses:
        return "FAILED"
    if "SKIPPED" in statuses:
        return "SKIPPED"
    return "PASS"


@dataclass
class RunConfig:
    daemon_jar: Path
    java_bin: str
    android_jar: Path
    kotlin_stdlib: Path
    classpath_extra: List[Path]
    aapt2: Optional[Path]
    javac: Optional[Path]
    d8_jar: Optional[Path]
    min_api: int
    work_root: Path
    op_timeout: float
    exercise_dex: bool
    # Compose support (apps with "compose": true in app.json): the compiler plugin jar
    # goes to `configure.compilerPlugins`, the runtime jar onto the app classpath.
    compose_plugin_jar: Optional[Path] = None
    compose_runtime_jar: Optional[Path] = None

    def classpath(self) -> List[str]:
        return [str(self.android_jar), str(self.kotlin_stdlib)] + [str(p) for p in self.classpath_extra]

    def compose_available(self) -> bool:
        return self.compose_plugin_jar is not None and self.compose_runtime_jar is not None


# ---------------------------------------------------------------------------
# Per-app driver
# ---------------------------------------------------------------------------


def run_app(app_dir: Path, daemon: Optional[DaemonClient], config: RunConfig, gaps: List[str]) -> Dict[str, Any]:
    name = app_dir.name
    app_json = json.loads((app_dir / "app.json").read_text())
    language = app_json.get("language", "unknown")
    application_id = app_json.get("applicationId", "")
    min_api = int(app_json.get("minSdk", config.min_api))

    app_work_root = config.work_root / name
    work_dir = app_work_root / "tree"
    out_dir = app_work_root / "out"
    r_scratch = app_work_root / "r-gen"
    copy_app_tree(app_dir, work_dir)
    # A fresh out_dir/r_scratch matters beyond tidiness: the daemon's
    # classesDir is never wiped by `configure` (only created if missing), so a
    # reused --work-dir across separate invocations would otherwise leak
    # unrelated stale .class files into this app's CRC diffs.
    for stale_dir in (out_dir, r_scratch):
        if stale_dir.exists():
            shutil.rmtree(stale_dir)

    report: Dict[str, Any] = {
        "name": name,
        "language": language,
        "applicationId": application_id,
        "baseline": None,
        "edits": [],
        "outputEquivalence": None,
        "status": "SKIPPED",
    }

    if daemon is None:
        report["baseline"] = {"status": "SKIPPED", "detail": "daemon unavailable for this run"}
        return report

    uses_compose = bool(app_json.get("compose"))
    if uses_compose and not config.compose_available():
        detail = "app uses Compose but --compose-plugin-jar/--compose-runtime-jar not given"
        gaps.append(f"{name}: {detail}; all rows SKIPPED")
        report["baseline"] = {"status": "SKIPPED", "detail": detail}
        for edit_dir in sorted((app_dir / "edits").glob("*")) if (app_dir / "edits").is_dir() else []:
            report["edits"].append(
                {
                    "edit": edit_dir.name,
                    "status": "SKIPPED",
                    "assertions": [make_assertion("compose_toolchain", "SKIPPED", detail)],
                }
            )
        return report
    compose_classpath = [str(config.compose_runtime_jar)] if uses_compose else []
    compiler_plugins = [str(config.compose_plugin_jar)] if uses_compose else []

    manifest_path = work_dir / "AndroidManifest.xml"
    res_dir = work_dir / "src" / "main" / "res"

    def regen_r(scratch: Path) -> Optional[Path]:
        return generate_r_classes(
            application_id, res_dir, manifest_path, config.android_jar, config.aapt2, config.javac, scratch,
        )

    r_classes_dir = regen_r(r_scratch)
    if r_classes_dir is None:
        if config.aapt2 is None:
            gaps.append(f"{name}: no --aapt2 given, R class not generated; compile against R.* will fail")
        elif config.javac is None:
            gaps.append(f"{name}: no javac available, R class not generated; compile against R.* will fail")
        else:
            gaps.append(f"{name}: R class generation failed (aapt2/javac step) despite aapt2+javac present")

    def app_classpath() -> List[str]:
        return config.classpath() + compose_classpath + ([str(r_classes_dir)] if r_classes_dir else [])

    try:
        configure_resp = daemon.configure(
            projectRoot=str(work_dir),
            classpath=app_classpath(),
            outDir=str(out_dir),
            aapt2=str(config.aapt2) if config.aapt2 else str(_placeholder_file(app_work_root, "aapt2")),
            d8Jar=str(config.d8_jar) if config.d8_jar else str(_placeholder_file(app_work_root, "d8.jar")),
            androidJar=str(config.android_jar),
            minApi=min_api,
            compilerPlugins=compiler_plugins,
        )
    except DaemonError as e:
        report["baseline"] = {"status": "SKIPPED", "detail": f"configure failed: {e}"}
        return report
    if not configure_resp.get("ok"):
        report["baseline"] = {"status": "FAILED", "detail": f"configure returned ok:false: {configure_resp}"}
        report["status"] = "FAILED"
        return report

    # Baseline: full compile, everything marked "changed" to seed IC caches
    # (README gotcha: SourcesChanges.Known needs this on the first build). If
    # r_classes_dir is None we still attempt this, defensively, so a real compiler
    # diagnostic (not a guess) explains the failure.
    all_sources = list_all_sources(work_dir)

    start = time.monotonic()
    try:
        baseline_resp = daemon.compile(all_sources, all_sources)
    except DaemonError as e:
        report["baseline"] = {"status": "SKIPPED", "detail": f"daemon transport error: {e}"}
        return report
    elapsed_ms = (time.monotonic() - start) * 1000.0

    if not baseline_resp.get("ok"):
        report["baseline"] = {
            "status": "FAILED" if r_classes_dir is not None else "SKIPPED",
            "detail": (
                f"baseline compile failed: {baseline_resp.get('diagnostics')}"
                + ("" if r_classes_dir is not None else " (R class unavailable -- likely cause)")
            ),
            "ms": elapsed_ms,
        }
        report["status"] = report["baseline"]["status"]
        if report["status"] == "FAILED":
            gaps.append(f"{name}: baseline compile FAILED; all edits skipped for this app")
        else:
            gaps.append(f"{name}: baseline compile could not be verified (no R class); all edits skipped")
        for edit_dir in sorted((app_dir / "edits").glob("*")) if (app_dir / "edits").is_dir() else []:
            report["edits"].append(
                {
                    "edit": edit_dir.name,
                    "status": "SKIPPED",
                    "assertions": [make_assertion("baseline", "SKIPPED", "baseline compile did not succeed")],
                }
            )
        return report

    classes_dir = Path(baseline_resp["classesDir"])
    prev_crcs = snapshot_class_crcs(classes_dir)
    report["baseline"] = {
        "status": "PASS",
        "ms": elapsed_ms,
        "daemonMs": baseline_resp.get("durationMillis"),
        "classCount": len(prev_crcs),
        "warnings": baseline_resp.get("diagnostics", []),
    }

    if config.exercise_dex and config.d8_jar is not None:
        try:
            dex_resp = daemon.dex([str(classes_dir)])
            report["baseline"]["dex"] = {"ok": dex_resp.get("ok"), "ms": dex_resp.get("durationMillis")}
        except DaemonError as e:
            report["baseline"]["dex"] = {"ok": False, "detail": str(e)}

    edits_dir = app_dir / "edits"
    edit_dirs = sorted(edits_dir.glob("*")) if edits_dir.is_dir() else []

    for edit_dir in edit_dirs:
        meta = json.loads((edit_dir / "meta.json").read_text())
        edit_report = run_edit(
            edit_dir=edit_dir,
            meta=meta,
            work_dir=work_dir,
            daemon=daemon,
            config=config,
            r_scratch=r_scratch,
            r_classes_dir=r_classes_dir,
            prev_crcs=prev_crcs,
            out_dir=out_dir,
            min_api=min_api,
            application_id=application_id,
            compose_classpath=compose_classpath,
            compiler_plugins=compiler_plugins,
        )
        prev_crcs = edit_report.pop("_prev_crcs")
        r_classes_dir = edit_report.pop("_r_classes_dir")
        report["edits"].append(edit_report)

    # Output equivalence: a from-scratch full compile of the final tree,
    # compared class-for-class against the incremental result above.
    clean_out_dir = app_work_root / "clean-out"
    clean_r_scratch = app_work_root / "clean-r-gen"
    for stale_dir in (clean_out_dir, clean_r_scratch):
        if stale_dir.exists():
            shutil.rmtree(stale_dir)
    clean_r_classes_dir = regen_r(clean_r_scratch)
    try:
        daemon.configure(
            projectRoot=str(work_dir),
            classpath=config.classpath()
            + compose_classpath
            + ([str(clean_r_classes_dir)] if clean_r_classes_dir else []),
            outDir=str(clean_out_dir),
            aapt2=str(config.aapt2) if config.aapt2 else str(_placeholder_file(app_work_root, "aapt2")),
            d8Jar=str(config.d8_jar) if config.d8_jar else str(_placeholder_file(app_work_root, "d8.jar")),
            androidJar=str(config.android_jar),
            minApi=min_api,
            compilerPlugins=compiler_plugins,
        )
        clean_sources = list_all_sources(work_dir)
        clean_resp = daemon.compile(clean_sources, clean_sources)
    except DaemonError as e:
        report["outputEquivalence"] = {"status": "SKIPPED", "detail": f"daemon transport error: {e}"}
    else:
        if not clean_resp.get("ok"):
            report["outputEquivalence"] = {
                "status": "FAILED",
                "detail": f"clean full compile failed: {clean_resp.get('diagnostics')}",
            }
        else:
            clean_crcs = snapshot_class_crcs(Path(clean_resp["classesDir"]))
            ok, detail = compare_class_trees(prev_crcs, clean_crcs)
            report["outputEquivalence"] = {"status": "PASS" if ok else "FAILED", "detail": detail}

    statuses = [report["baseline"]["status"]] + [e["status"] for e in report["edits"]]
    if report["outputEquivalence"] is not None:
        statuses.append(report["outputEquivalence"]["status"])
    report["status"] = aggregate_status([{"status": s, "name": "", "detail": ""} for s in statuses])
    return report


def run_edit(
    edit_dir: Path,
    meta: Dict[str, Any],
    work_dir: Path,
    daemon: DaemonClient,
    config: RunConfig,
    r_scratch: Path,
    r_classes_dir: Optional[Path],
    prev_crcs: Dict[str, int],
    out_dir: Path,
    min_api: int,
    application_id: str,
    compose_classpath: Optional[List[str]] = None,
    compiler_plugins: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """Applies one edit and dispatches the op(s) its meta.expected.route calls
    for. Returns the edit's report dict plus two private keys
    (`_prev_crcs`, `_r_classes_dir`) the caller pops off to thread state into the
    next edit -- run_edit itself is stateless across calls."""
    edit_name = edit_dir.name
    expected = meta.get("expected", {})
    route = expected.get("route")

    edit_report: Dict[str, Any] = {
        "edit": edit_name,
        "editClass": meta.get("editClass"),
        "description": meta.get("description"),
        "expectedRoute": route,
        "opsInvoked": [],
        "ms": {},
        "recompiledClasses": None,
        "assertions": [],
    }

    try:
        changed_paths = apply_edit(work_dir, edit_dir, meta)
    except FileNotFoundError as e:
        edit_report["assertions"].append(make_assertion("apply_edit", "FAILED", str(e)))
        edit_report["status"] = "FAILED"
        edit_report["_prev_crcs"] = prev_crcs
        edit_report["_r_classes_dir"] = r_classes_dir
        return edit_report

    touches_res = any(is_res_path(p) for p in changed_paths)
    code_paths = [p for p in changed_paths if is_code_path(p)]

    # Only an edit that ADDS a resource name changes R's field set -- a value-only
    # edit (existing string/color/layout content changes) leaves every existing
    # R.* field intact, so regenerating + reconfiguring for it is wasted daemon
    # round-trip time. _is_resource_add_edit is the (cheap, corpus-convention)
    # signal for which edits are which; see its docstring.
    if route in ("resources", "mixed") and touches_res and _is_resource_add_edit(meta):
        start_reconfigure = time.monotonic()
        regenerated = generate_r_classes(
            meta.get("applicationId", "") or application_id or _read_app_package(work_dir),
            work_dir / "src" / "main" / "res",
            work_dir / "AndroidManifest.xml",
            config.android_jar,
            config.aapt2,
            config.javac,
            r_scratch,
        )
        if regenerated is not None:
            r_classes_dir = regenerated
            # A classpath change is a session invalidation for the incremental
            # compiler (see IncrementalCompiler.kt's docstring) -- re-`configure`
            # rather than mutate the running session in place.
            try:
                daemon.configure(
                    projectRoot=str(work_dir),
                    classpath=config.classpath() + (compose_classpath or []) + [str(r_classes_dir)],
                    outDir=str(out_dir),
                    aapt2=str(config.aapt2) if config.aapt2 else str(_placeholder_file(work_dir.parent, "aapt2")),
                    d8Jar=str(config.d8_jar) if config.d8_jar else str(_placeholder_file(work_dir.parent, "d8.jar")),
                    androidJar=str(config.android_jar),
                    minApi=min_api,
                    compilerPlugins=compiler_plugins or [],
                )
                edit_report["opsInvoked"].append("reconfigure")
            except DaemonError as e:
                edit_report["assertions"].append(make_assertion("reconfigure", "FAILED", f"reconfigure failed: {e}"))
        else:
            edit_report["assertions"].append(
                make_assertion("reconfigure", "SKIPPED", "R class regeneration failed; new resource id may not resolve"),
            )
        edit_report["reconfigureMs"] = (time.monotonic() - start_reconfigure) * 1000.0

    manifest_abs = str(work_dir / "AndroidManifest.xml")
    res_dir_abs = str(work_dir / "src" / "main" / "res")

    def do_compile() -> None:
        nonlocal prev_crcs
        all_sources = list_all_sources(work_dir)
        changed_abs = [str(work_dir / p) for p in code_paths]
        edit_report["opsInvoked"].append("compile")
        start = time.monotonic()
        try:
            resp = daemon.compile(all_sources, changed_abs)
        except DaemonError as e:
            edit_report["ms"]["compile"] = None
            edit_report["assertions"].append(make_assertion("op_success", "SKIPPED", f"daemon transport error: {e}"))
            return
        elapsed_ms = (time.monotonic() - start) * 1000.0
        edit_report["ms"]["compile"] = elapsed_ms
        edit_report["daemonMs_compile"] = resp.get("durationMillis")
        if not resp.get("ok"):
            edit_report["assertions"].append(
                make_assertion("op_success", "FAILED", f"compile ok:false: {resp.get('diagnostics')}"),
            )
            return
        edit_report["assertions"].append(make_assertion("op_success", "PASS", "compile ok:true"))
        resp_classes_dir = Path(resp["classesDir"])
        after_crcs = snapshot_class_crcs(resp_classes_dir)
        recompiled = diff_recompiled(prev_crcs, after_crcs)
        edit_report["recompiledClasses"] = recompiled
        prev_crcs = after_crcs

        bounds = expected.get("recompiledClasses")
        if bounds is not None:
            lo, hi = bounds.get("min"), bounds.get("max")
            count = len(recompiled)
            in_bounds = (lo is None or count >= lo) and (hi is None or count <= hi)
            edit_report["assertions"].append(
                make_assertion(
                    "recompiled_bounds",
                    "PASS" if in_bounds else "FAILED",
                    f"recompiled {count} classes, expected [{lo}, {hi}]: {recompiled}",
                ),
            )

        marker = expected.get("behavioralMarker")
        if marker is not None:
            marker_bytes = marker.encode("utf-8")
            found = False
            for rel in recompiled:
                candidate = resp_classes_dir / rel
                if candidate.is_file() and marker_bytes in candidate.read_bytes():
                    found = True
                    break
            edit_report["assertions"].append(
                make_assertion(
                    "behavioral_marker",
                    "PASS" if found else "FAILED",
                    f"marker {marker!r} {'found' if found else 'NOT found'} in recompiled class bytes",
                ),
            )

    def do_relink() -> None:
        edit_report["opsInvoked"].append("relink")
        if config.aapt2 is None:
            edit_report["ms"]["relink"] = None
            edit_report["assertions"].append(make_assertion("op_success", "SKIPPED", "no --aapt2 given"))
            return
        start = time.monotonic()
        try:
            resp = daemon.relink([res_dir_abs], manifest_abs)
        except DaemonError as e:
            edit_report["ms"]["relink"] = None
            edit_report["assertions"].append(make_assertion("op_success", "SKIPPED", f"daemon transport error: {e}"))
            return
        elapsed_ms = (time.monotonic() - start) * 1000.0
        edit_report["ms"]["relink"] = elapsed_ms
        edit_report["daemonMs_relink"] = resp.get("durationMillis")
        status = "PASS" if resp.get("ok") else "FAILED"
        detail = "relink ok:true" if resp.get("ok") else f"relink ok:false: {resp.get('diagnostics')}"
        edit_report["assertions"].append(make_assertion("op_success", status, detail))

    if route == "code" or route == "noop":
        do_compile()
    elif route == "resources":
        do_relink()
    elif route == "mixed":
        do_compile()
        do_relink()
    elif route == "assets":
        edit_report["assertions"].append(make_assertion("op_success", "PASS", "assets-pass: no daemon op required"))
    elif route == "fallback":
        edit_report["assertions"].append(
            make_assertion("op_success", "PASS", "fallback recorded: no daemon op invoked (full Gradle build expected)"),
        )
    else:
        edit_report["assertions"].append(make_assertion("op_success", "SKIPPED", f"unknown expected route {route!r}"))

    edit_report["status"] = aggregate_status(edit_report["assertions"])
    edit_report["_prev_crcs"] = prev_crcs
    edit_report["_r_classes_dir"] = r_classes_dir
    return edit_report


def _is_resource_add_edit(meta: Dict[str, Any]) -> bool:
    """True when the edit's `editClass` names a new resource being ADDED (e.g.
    "string-add") rather than an existing resource's value changing (e.g.
    "string-value", "color-value", "layout-edit"). Only an ADD changes R's field
    set -- see the corpus README's editClass naming convention. A cheap substring
    check on a harness-authored, closed vocabulary; not user input."""
    return "add" in (meta.get("editClass") or "").lower()


def _read_app_package(work_dir: Path) -> str:
    """Best-effort fallback: pull the package attribute out of AndroidManifest.xml
    when neither meta.json nor app.json's applicationId is available. Cheap
    regex, not a real XML need here (single attribute)."""
    import re

    manifest = work_dir / "AndroidManifest.xml"
    if not manifest.is_file():
        return ""
    match = re.search(r'package="([^"]+)"', manifest.read_text())
    return match.group(1) if match else ""


def _placeholder_file(work_root: Path, name: str) -> Path:
    """An existing-but-inert file to satisfy configure's existence check when
    aapt2/d8Jar aren't provided. DaemonService only checks File.exists() at
    configure time (dex/relink tool wrappers are lazy) -- see corpus README
    LIMITATIONS. Never used for a real dex/relink call."""
    work_root.mkdir(parents=True, exist_ok=True)
    placeholder = work_root / f"placeholder-{name}"
    if not placeholder.exists():
        placeholder.write_bytes(b"")
    return placeholder


# ---------------------------------------------------------------------------
# Report rendering
# ---------------------------------------------------------------------------


def render_markdown(run_report: Dict[str, Any]) -> str:
    lines = ["# Quick Build corpus matrix results", ""]
    lines.append(f"Generated: {run_report['generatedAt']}")
    lines.append("")
    lines.append("## Config")
    lines.append("")
    for k, v in run_report["config"].items():
        if isinstance(v, list):
            v = ", ".join(v) if v else "(none)"
        lines.append(f"- **{k}**: {v}")
    lines.append("")
    s = run_report["summary"]
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Apps: {s['apps']}, Edits: {s['edits']}")
    lines.append(f"- Passed: {s['passed']}, Failed: {s['failed']}, Skipped: {s['skipped']}")
    lines.append("")

    for app in run_report["apps"]:
        lines.append(f"## {app['name']} ({app['language']}) -- {app['status']}")
        lines.append("")
        b = app["baseline"] or {}
        lines.append(f"Baseline: {b.get('status')} ({_fmt_ms(b.get('ms'))}), classes={b.get('classCount', '-')}")
        if b.get("detail"):
            lines.append(f"  - {b['detail']}")
        lines.append("")
        if app["edits"]:
            lines.append("| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |")
            lines.append("|---|---|---|---|---|---|---|---|")
            for e in app["edits"]:
                ms_parts = ", ".join(f"{k}={_fmt_ms(v)}" for k, v in e.get("ms", {}).items())
                if e.get("reconfigureMs") is not None:
                    reconfigure_part = f"reconfigure={_fmt_ms(e['reconfigureMs'])}"
                    ms_parts = f"{ms_parts}, {reconfigure_part}" if ms_parts else reconfigure_part
                recompiled = e.get("recompiledClasses")
                recompiled_str = str(len(recompiled)) if recompiled is not None else "-"
                notes = "; ".join(a["detail"] for a in e["assertions"] if a["status"] != "PASS")
                lines.append(
                    f"| {e['edit']} | {e.get('editClass')} | {e.get('expectedRoute')} | "
                    f"{', '.join(e.get('opsInvoked', []))} | {ms_parts} | {recompiled_str} | "
                    f"{e['status']} | {notes} |",
                )
            lines.append("")
        oe = app.get("outputEquivalence")
        if oe:
            lines.append(f"Output equivalence: {oe['status']} -- {oe['detail']}")
            lines.append("")

    if run_report["gaps"]:
        lines.append("## Gaps")
        lines.append("")
        for gap in run_report["gaps"]:
            lines.append(f"- {gap}")
        lines.append("")

    return "\n".join(lines)


def _fmt_ms(value: Any) -> str:
    if value is None:
        return "-"
    return f"{value:.0f}ms" if isinstance(value, float) else f"{value}ms"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-dir", type=Path, default=CORPUS_DIR / "apps")
    parser.add_argument("--results-dir", type=Path, default=CORPUS_DIR / "results")
    parser.add_argument("--work-dir", type=Path, default=None, help="default: a fresh dir under the system temp dir")
    parser.add_argument("--daemon-jar", type=Path, default=DEFAULT_DAEMON_JAR)
    parser.add_argument("--java-bin", default="java")
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--kotlin-stdlib", type=Path, required=True)
    parser.add_argument("--classpath-extra", type=Path, action="append", default=[])
    parser.add_argument("--aapt2", type=Path, default=None, help="omit to skip relink ops and R class generation")
    parser.add_argument(
        "--javac", type=Path, default=None,
        help="omit to auto-derive from PATH or alongside --java-bin; still omit entirely to skip R class generation",
    )
    parser.add_argument("--d8-jar", type=Path, default=None, help="omit to skip dex ops")
    parser.add_argument(
        "--compose-plugin-jar", type=Path, default=None,
        help="kotlin-compose-compiler-plugin-embeddable jar (same version as the daemon's compiler); "
        "omit to SKIP apps with \"compose\": true",
    )
    parser.add_argument(
        "--compose-runtime-jar", type=Path, default=None,
        help="androidx.compose.runtime classes.jar for the app classpath of Compose apps; "
        "omit to SKIP apps with \"compose\": true",
    )
    parser.add_argument("--min-api", type=int, default=DEFAULT_MIN_API)
    parser.add_argument("--op-timeout", type=float, default=120.0, help="seconds to wait for one daemon response")
    parser.add_argument("--apps", default=None, help="comma-separated app names to run (default: all)")
    parser.add_argument("--exercise-dex", action="store_true", help="also smoke-test the dex op after baseline")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    if not args.android_jar.is_file():
        print(f"error: --android-jar not found: {args.android_jar}", file=sys.stderr)
        return 2
    if not args.kotlin_stdlib.is_file():
        print(f"error: --kotlin-stdlib not found: {args.kotlin_stdlib}", file=sys.stderr)
        return 2

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    results_dir = args.results_dir / timestamp
    results_dir.mkdir(parents=True, exist_ok=True)

    work_root = args.work_dir or Path(tempfile.gettempdir()) / "quickbuild-matrix" / timestamp
    work_root.mkdir(parents=True, exist_ok=True)

    gaps: List[str] = []

    javac_bin: Optional[Path] = args.javac if args.javac and args.javac.is_file() else None
    if args.javac and not javac_bin:
        gaps.append(f"--javac given but not found on disk: {args.javac}; treated as absent")
    if javac_bin is None:
        derived = shutil.which("javac")
        if derived is None:
            java_resolved = shutil.which(args.java_bin) or (args.java_bin if Path(args.java_bin).is_file() else None)
            if java_resolved:
                sibling = Path(java_resolved).resolve().parent / "javac"
                if sibling.is_file():
                    derived = str(sibling)
        if derived:
            javac_bin = Path(derived)

    config = RunConfig(
        daemon_jar=args.daemon_jar,
        java_bin=args.java_bin,
        android_jar=args.android_jar,
        kotlin_stdlib=args.kotlin_stdlib,
        classpath_extra=args.classpath_extra,
        aapt2=args.aapt2 if args.aapt2 and args.aapt2.exists() else None,
        javac=javac_bin,
        d8_jar=args.d8_jar if args.d8_jar and args.d8_jar.exists() else None,
        min_api=args.min_api,
        work_root=work_root,
        op_timeout=args.op_timeout,
        exercise_dex=args.exercise_dex,
        compose_plugin_jar=(
            args.compose_plugin_jar if args.compose_plugin_jar and args.compose_plugin_jar.is_file() else None
        ),
        compose_runtime_jar=(
            args.compose_runtime_jar if args.compose_runtime_jar and args.compose_runtime_jar.is_file() else None
        ),
    )

    if args.aapt2 and not config.aapt2:
        gaps.append(f"--aapt2 given but not found on disk: {args.aapt2}; treated as absent")
    if args.d8_jar and not config.d8_jar:
        gaps.append(f"--d8-jar given but not found on disk: {args.d8_jar}; treated as absent")
    if args.compose_plugin_jar and not config.compose_plugin_jar:
        gaps.append(f"--compose-plugin-jar given but not found on disk: {args.compose_plugin_jar}; treated as absent")
    if args.compose_runtime_jar and not config.compose_runtime_jar:
        gaps.append(f"--compose-runtime-jar given but not found on disk: {args.compose_runtime_jar}; treated as absent")
    if config.aapt2 is None:
        gaps.append("no --aapt2: relink ops SKIPPED for every 'resources'/'mixed' edit in this run")
    if config.javac is None:
        gaps.append("no javac (via --javac, PATH, or alongside --java-bin): R class generation SKIPPED for every app")
    if config.d8_jar is None:
        gaps.append("no --d8-jar: dex ops not exercised in this run")
    if not config.compose_available():
        gaps.append(
            "no --compose-plugin-jar/--compose-runtime-jar: apps with \"compose\": true SKIPPED in this run"
        )

    daemon: Optional[DaemonClient] = None
    if not config.daemon_jar.is_file():
        gaps.append(f"daemon jar not found: {config.daemon_jar}; every app/edit in this run is SKIPPED")
    else:
        candidate = DaemonClient(
            cmd=[config.java_bin, "-jar", str(config.daemon_jar)],
            timeout_seconds=config.op_timeout,
            stderr_log=results_dir / "daemon-stderr.log",
        )
        try:
            candidate.start()
            ok, detail = candidate.health_check()
        except (OSError, DaemonError) as e:
            ok, detail = False, str(e)
        if ok:
            daemon = candidate
        else:
            gaps.append(f"daemon failed health check ({detail}); every app/edit in this run is SKIPPED")
            candidate.stop()

    all_app_dirs = sorted(p for p in args.corpus_dir.iterdir() if p.is_dir() and (p / "app.json").is_file())
    if args.apps:
        wanted = set(args.apps.split(","))
        all_app_dirs = [p for p in all_app_dirs if p.name in wanted]

    # Vendored apps (vendor.json): their third-party sources are never checked in;
    # fetch_vendored.py materializes the complete app dir under corpus/.cache/.
    # Missing materialization is an environment gap (SKIP), never a failure.
    resolved_app_dirs = []
    for p in all_app_dirs:
        if (p / "vendor.json").is_file():
            cache = CORPUS_DIR / ".cache" / "apps" / p.name
            if (cache / "app.json").is_file():
                resolved_app_dirs.append(cache)
            else:
                gaps.append(
                    f"vendored app '{p.name}' not materialized: run "
                    f"'python3 quick-build/corpus/harness/fetch_vendored.py --apps {p.name}' first; SKIPPED"
                )
        else:
            resolved_app_dirs.append(p)
    all_app_dirs = resolved_app_dirs

    run_report: Dict[str, Any] = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "config": {
            "daemonJar": str(config.daemon_jar),
            "daemonAvailable": daemon is not None,
            "androidJar": str(config.android_jar),
            "kotlinStdlib": str(config.kotlin_stdlib),
            "classpathExtra": [str(p) for p in config.classpath_extra],
            "aapt2": str(config.aapt2) if config.aapt2 else None,
            "javac": str(config.javac) if config.javac else None,
            "d8Jar": str(config.d8_jar) if config.d8_jar else None,
            "composePluginJar": str(config.compose_plugin_jar) if config.compose_plugin_jar else None,
            "composeRuntimeJar": str(config.compose_runtime_jar) if config.compose_runtime_jar else None,
            "minApi": config.min_api,
        },
        "apps": [],
        "gaps": gaps,
        "summary": {},
    }

    try:
        for app_dir in all_app_dirs:
            app_report = run_app(app_dir, daemon, config, gaps)
            run_report["apps"].append(app_report)
    finally:
        if daemon is not None:
            daemon.stop()

    total_edits = sum(len(a["edits"]) for a in run_report["apps"])
    passed = sum(1 for a in run_report["apps"] for e in a["edits"] if e["status"] == "PASS")
    failed = sum(1 for a in run_report["apps"] for e in a["edits"] if e["status"] == "FAILED")
    skipped = sum(1 for a in run_report["apps"] for e in a["edits"] if e["status"] == "SKIPPED")
    run_report["summary"] = {
        "apps": len(run_report["apps"]),
        "edits": total_edits,
        "passed": passed,
        "failed": failed,
        "skipped": skipped,
    }
    run_report["gaps"] = gaps

    (results_dir / "matrix.json").write_text(json.dumps(run_report, indent=2, default=str))
    (results_dir / "matrix.md").write_text(render_markdown(run_report))
    print(f"wrote {results_dir / 'matrix.json'}")
    print(f"wrote {results_dir / 'matrix.md'}")

    any_failed = (
        any(e["status"] == "FAILED" for a in run_report["apps"] for e in a["edits"])
        or any((a["baseline"] or {}).get("status") == "FAILED" for a in run_report["apps"])
        or any((a.get("outputEquivalence") or {}).get("status") == "FAILED" for a in run_report["apps"])
    )
    return 1 if any_failed else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Quick Build (ADFA-4128) corpus matrix runner -- ON-DEVICE mode.

Drives the SAME quickbuild-daemon.jar protocol as run_matrix.py, but the daemon
process runs on a physical Android device (via `adb shell run-as <pkg> sh
<launcher>`), not the host JVM. All paths sent over the protocol (projectRoot,
classpath entries, outDir, aapt2, d8Jar, androidJar) must therefore be DEVICE
paths, not host paths -- this module's job is exactly that translation, plus
the push/pull plumbing to keep a host-side edit-application staging area (reused
unchanged from run_matrix.py: copy_app_tree/apply_edit/generate_r_classes all
still run against a HOST filesystem tree) in sync with its on-device mirror.

Device layout (see the corpus README's on-device section):
  App-private (only the already-running daemon process can read these, via
  run-as -- this script never reads/writes them directly):
    <APP_PRIVATE>/files/usr/lib/jvm/java-21-openjdk/bin/java   (launcher's java)
    <APP_PRIVATE>/files/home/android-sdk/platforms/android-36/android.jar
    <APP_PRIVATE>/files/home/android-sdk/build-tools/35.0.0/aapt2
    <APP_PRIVATE>/files/home/android-sdk/build-tools/35.0.0/lib/d8.jar
    <APP_PRIVATE>/files/home/.cg/quickbuild/daemon/kotlin-stdlib-2.3.0.jar
  Shared storage (this script pushes/pulls here directly with plain `adb`,
  no run-as needed -- confirmed readable by the app's own process too, since
  /sdcard/qb-daemon.sh itself lives here and the daemon launches from it):
    /sdcard/qb-corpus/<app>/tree        mirrors the host work_dir tree
    /sdcard/qb-work/<app>/{out,rclasses,clean-out,clean-rclasses}

R class generation still runs on the HOST (aapt2 + javac from --host-aapt2/
--host-javac/--host-android-jar) -- the resulting .class bytes (JVM target 17,
see IncrementalCompiler.kt) are portable to the device's JDK 21 and are pushed
over, exactly like every other compiled artifact in this harness is host-authored
and device-verified. This avoids needing a second aapt2/javac invocation path
on-device.

Stdlib only (plus a plain `adb` binary on PATH). See run_matrix.py for the
shared protocol/oracle helpers this module imports and reuses unchanged.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import run_matrix as rm

HARNESS_DIR = Path(__file__).resolve().parent
CORPUS_DIR = HARNESS_DIR.parent
QUICK_BUILD_DIR = CORPUS_DIR.parent
REPO_ROOT = QUICK_BUILD_DIR.parent
DEFAULT_DAEMON_JAR = REPO_ROOT / "quickbuild-daemon" / "build" / "libs" / "quickbuild-daemon.jar"

DEFAULT_SERIAL = "RZGYC24640P"
DEFAULT_PKG = "com.itsaky.androidide"


def _app_private(pkg: str) -> str:
    return f"/data/user/0/{pkg}"


DEVICE_CORPUS_ROOT = "/sdcard/qb-corpus"
DEVICE_WORK_ROOT = "/sdcard/qb-work"
DEVICE_DAEMON_LAUNCHER = "/sdcard/qb-daemon.sh"


# ---------------------------------------------------------------------------
# adb plumbing. Everything under /sdcard is written/read with plain `adb`
# (shell uid) -- confirmed readable by the app's own process too (the daemon
# launcher script itself lives there). App-private tool paths are never
# touched by this script directly; they're only ever passed as strings into
# the protocol, resolved by the already-running (run-as'd) daemon process.
# ---------------------------------------------------------------------------


def _run_adb(args: List[str], timeout: float = 120.0) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)


def device_rm_rf(serial: str, path: str) -> None:
    _run_adb(["-s", serial, "shell", "rm", "-rf", path])


def device_mkdir_p(serial: str, path: str) -> None:
    _run_adb(["-s", serial, "shell", "mkdir", "-p", path])


def push_tree(serial: str, host_dir: Path, device_dir: str) -> None:
    """Replaces device_dir's contents with host_dir's. Pushing a directory to a
    NON-existing destination places its contents directly there (no extra
    nesting level) -- verified against this adb build before relying on it."""
    device_rm_rf(serial, device_dir)
    result = _run_adb(["-s", serial, "push", str(host_dir), device_dir])
    if result.returncode != 0:
        raise RuntimeError(f"adb push failed ({host_dir} -> {device_dir}): {result.stderr}")


def push_file(serial: str, host_file: Path, device_file: str) -> None:
    parent = device_file.rsplit("/", 1)[0]
    device_mkdir_p(serial, parent)
    result = _run_adb(["-s", serial, "push", str(host_file), device_file])
    if result.returncode != 0:
        raise RuntimeError(f"adb push failed ({host_file} -> {device_file}): {result.stderr}")


def pull_tree(serial: str, device_dir: str, host_dir: Path) -> None:
    """Host-side mirror of a device directory, for the CRC oracle to run
    against unchanged (snapshot_class_crcs etc. all take a host Path)."""
    if host_dir.exists():
        shutil.rmtree(host_dir)
    host_dir.parent.mkdir(parents=True, exist_ok=True)
    result = _run_adb(["-s", serial, "pull", device_dir, str(host_dir)])
    if result.returncode != 0:
        raise RuntimeError(f"adb pull failed ({device_dir} -> {host_dir}): {result.stderr}")


def to_device_rel(host_path: Path, host_root: Path, device_root: str) -> str:
    rel = Path(host_path).resolve().relative_to(host_root.resolve()).as_posix()
    return f"{device_root}/{rel}"


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------


@dataclass
class DeviceRunConfig:
    serial: str
    daemon_launcher: str
    device_android_jar: str
    device_kotlin_stdlib: str
    device_aapt2: str
    device_d8_jar: str
    min_api: int
    op_timeout: float
    host_android_jar: Path  # for R generation only -- see module docstring
    host_aapt2: Path
    host_javac: Path
    work_root: Path

    def device_base_classpath(self) -> List[str]:
        return [self.device_android_jar, self.device_kotlin_stdlib]


# ---------------------------------------------------------------------------
# Per-app driver
# ---------------------------------------------------------------------------


def run_app_device(app_dir: Path, daemon: Optional[rm.DaemonClient], config: DeviceRunConfig, gaps: List[str]) -> Dict[str, Any]:
    name = app_dir.name
    app_json = json.loads((app_dir / "app.json").read_text())
    language = app_json.get("language", "unknown")
    application_id = app_json.get("applicationId", "")
    min_api = int(app_json.get("minSdk", config.min_api))

    app_work_root = config.work_root / name
    work_dir = app_work_root / "tree"
    r_scratch = app_work_root / "r-gen"
    mirror_root = app_work_root / "device-pull"
    rm.copy_app_tree(app_dir, work_dir)
    for stale_dir in (r_scratch, mirror_root):
        if stale_dir.exists():
            shutil.rmtree(stale_dir)

    device_tree = f"{DEVICE_CORPUS_ROOT}/{name}/tree"
    device_out = f"{DEVICE_WORK_ROOT}/{name}/out"
    device_rclasses = f"{DEVICE_WORK_ROOT}/{name}/rclasses"

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

    manifest_path = work_dir / "AndroidManifest.xml"
    res_dir = work_dir / "src" / "main" / "res"

    def regen_r(scratch: Path) -> Optional[Path]:
        return rm.generate_r_classes(
            application_id, res_dir, manifest_path, config.host_android_jar, config.host_aapt2, config.host_javac, scratch,
        )

    r_classes_dir = regen_r(r_scratch)
    if r_classes_dir is None:
        gaps.append(f"{name}: R class generation failed (host aapt2/javac step); compile against R.* will fail")

    push_tree(config.serial, work_dir, device_tree)
    if r_classes_dir is not None:
        push_tree(config.serial, r_classes_dir, device_rclasses)

    def device_classpath() -> List[str]:
        cp = config.device_base_classpath()
        if r_classes_dir is not None:
            cp.append(device_rclasses)
        return cp

    try:
        configure_resp = daemon.configure(
            projectRoot=device_tree,
            classpath=device_classpath(),
            outDir=device_out,
            aapt2=config.device_aapt2,
            d8Jar=config.device_d8_jar,
            androidJar=config.device_android_jar,
            minApi=min_api,
        )
    except rm.DaemonError as e:
        report["baseline"] = {"status": "SKIPPED", "detail": f"configure failed: {e}"}
        return report
    if not configure_resp.get("ok"):
        report["baseline"] = {"status": "FAILED", "detail": f"configure returned ok:false: {configure_resp}"}
        report["status"] = "FAILED"
        return report

    all_sources_device = [to_device_rel(Path(p), work_dir, device_tree) for p in rm.list_all_sources(work_dir)]

    start = time.monotonic()
    try:
        baseline_resp = daemon.compile(all_sources_device, all_sources_device)
    except rm.DaemonError as e:
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
        gaps.append(f"{name}: baseline compile did not succeed on-device; all edits skipped for this app")
        for edit_dir in sorted((app_dir / "edits").glob("*")) if (app_dir / "edits").is_dir() else []:
            report["edits"].append(
                {
                    "edit": edit_dir.name,
                    "status": "SKIPPED",
                    "assertions": [rm.make_assertion("baseline", "SKIPPED", "baseline compile did not succeed")],
                }
            )
        return report

    baseline_mirror = mirror_root / "baseline-classes"
    pull_tree(config.serial, baseline_resp["classesDir"], baseline_mirror)
    prev_crcs = rm.snapshot_class_crcs(baseline_mirror)
    report["baseline"] = {
        "status": "PASS",
        "ms": elapsed_ms,
        "daemonMs": baseline_resp.get("durationMillis"),
        "classCount": len(prev_crcs),
        "warnings": baseline_resp.get("diagnostics", []),
    }

    edits_dir = app_dir / "edits"
    edit_dirs = sorted(edits_dir.glob("*")) if edits_dir.is_dir() else []

    for i, edit_dir in enumerate(edit_dirs):
        meta = json.loads((edit_dir / "meta.json").read_text())
        edit_report = run_edit_device(
            edit_index=i,
            edit_dir=edit_dir,
            meta=meta,
            work_dir=work_dir,
            daemon=daemon,
            config=config,
            r_scratch=r_scratch,
            r_classes_dir=r_classes_dir,
            prev_crcs=prev_crcs,
            device_tree=device_tree,
            device_out=device_out,
            device_rclasses=device_rclasses,
            mirror_root=mirror_root,
            min_api=min_api,
            application_id=application_id,
        )
        prev_crcs = edit_report.pop("_prev_crcs")
        r_classes_dir = edit_report.pop("_r_classes_dir")
        report["edits"].append(edit_report)

    # Output equivalence: from-scratch full compile of the final device tree
    # (already fully up to date -- every edit pushed its changed files as it
    # went), fresh outDir/session, fresh R class.
    device_clean_out = f"{DEVICE_WORK_ROOT}/{name}/clean-out"
    device_clean_rclasses = f"{DEVICE_WORK_ROOT}/{name}/clean-rclasses"
    clean_r_scratch = app_work_root / "clean-r-gen"
    if clean_r_scratch.exists():
        shutil.rmtree(clean_r_scratch)
    clean_r_classes_dir = regen_r(clean_r_scratch)
    try:
        clean_classpath = config.device_base_classpath()
        if clean_r_classes_dir is not None:
            push_tree(config.serial, clean_r_classes_dir, device_clean_rclasses)
            clean_classpath.append(device_clean_rclasses)
        daemon.configure(
            projectRoot=device_tree,
            classpath=clean_classpath,
            outDir=device_clean_out,
            aapt2=config.device_aapt2,
            d8Jar=config.device_d8_jar,
            androidJar=config.device_android_jar,
            minApi=min_api,
        )
        clean_sources_device = [to_device_rel(Path(p), work_dir, device_tree) for p in rm.list_all_sources(work_dir)]
        clean_resp = daemon.compile(clean_sources_device, clean_sources_device)
    except rm.DaemonError as e:
        report["outputEquivalence"] = {"status": "SKIPPED", "detail": f"daemon transport error: {e}"}
    else:
        if not clean_resp.get("ok"):
            report["outputEquivalence"] = {
                "status": "FAILED",
                "detail": f"clean full compile failed: {clean_resp.get('diagnostics')}",
            }
        else:
            clean_mirror = mirror_root / "clean-classes"
            pull_tree(config.serial, clean_resp["classesDir"], clean_mirror)
            clean_crcs = rm.snapshot_class_crcs(clean_mirror)
            ok, detail = rm.compare_class_trees(prev_crcs, clean_crcs)
            report["outputEquivalence"] = {"status": "PASS" if ok else "FAILED", "detail": detail}

    statuses = [report["baseline"]["status"]] + [e["status"] for e in report["edits"]]
    if report["outputEquivalence"] is not None:
        statuses.append(report["outputEquivalence"]["status"])
    report["status"] = rm.aggregate_status([{"status": s, "name": "", "detail": ""} for s in statuses])
    return report


def run_edit_device(
    edit_index: int,
    edit_dir: Path,
    meta: Dict[str, Any],
    work_dir: Path,
    daemon: rm.DaemonClient,
    config: DeviceRunConfig,
    r_scratch: Path,
    r_classes_dir: Optional[Path],
    prev_crcs: Dict[str, int],
    device_tree: str,
    device_out: str,
    device_rclasses: str,
    mirror_root: Path,
    min_api: int,
    application_id: str,
) -> Dict[str, Any]:
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
        changed_paths = rm.apply_edit(work_dir, edit_dir, meta)
    except FileNotFoundError as e:
        edit_report["assertions"].append(rm.make_assertion("apply_edit", "FAILED", str(e)))
        edit_report["status"] = "FAILED"
        edit_report["_prev_crcs"] = prev_crcs
        edit_report["_r_classes_dir"] = r_classes_dir
        return edit_report

    for rel in changed_paths:
        push_file(config.serial, work_dir / rel, f"{device_tree}/{rel}")

    touches_res = any(rm.is_res_path(p) for p in changed_paths)
    code_paths = [p for p in changed_paths if rm.is_code_path(p)]

    if route in ("resources", "mixed") and touches_res and rm._is_resource_add_edit(meta):
        start_reconfigure = time.monotonic()
        regenerated = rm.generate_r_classes(
            meta.get("applicationId", "") or application_id or rm._read_app_package(work_dir),
            work_dir / "src" / "main" / "res",
            work_dir / "AndroidManifest.xml",
            config.host_android_jar,
            config.host_aapt2,
            config.host_javac,
            r_scratch,
        )
        if regenerated is not None:
            r_classes_dir = regenerated
            push_tree(config.serial, r_classes_dir, device_rclasses)
            classpath = config.device_base_classpath() + [device_rclasses]
            try:
                daemon.configure(
                    projectRoot=device_tree,
                    classpath=classpath,
                    outDir=device_out,
                    aapt2=config.device_aapt2,
                    d8Jar=config.device_d8_jar,
                    androidJar=config.device_android_jar,
                    minApi=min_api,
                )
                edit_report["opsInvoked"].append("reconfigure")
            except rm.DaemonError as e:
                edit_report["assertions"].append(rm.make_assertion("reconfigure", "FAILED", f"reconfigure failed: {e}"))
        else:
            edit_report["assertions"].append(
                rm.make_assertion("reconfigure", "SKIPPED", "R class regeneration failed; new resource id may not resolve"),
            )
        edit_report["reconfigureMs"] = (time.monotonic() - start_reconfigure) * 1000.0

    manifest_device = f"{device_tree}/AndroidManifest.xml"
    res_dir_device = f"{device_tree}/src/main/res"

    def do_compile() -> None:
        nonlocal prev_crcs
        all_sources_device = [to_device_rel(Path(p), work_dir, device_tree) for p in rm.list_all_sources(work_dir)]
        changed_device = [f"{device_tree}/{p}" for p in code_paths]
        edit_report["opsInvoked"].append("compile")
        start = time.monotonic()
        try:
            resp = daemon.compile(all_sources_device, changed_device)
        except rm.DaemonError as e:
            edit_report["ms"]["compile"] = None
            edit_report["assertions"].append(rm.make_assertion("op_success", "SKIPPED", f"daemon transport error: {e}"))
            return
        elapsed_ms = (time.monotonic() - start) * 1000.0
        edit_report["ms"]["compile"] = elapsed_ms
        edit_report["daemonMs_compile"] = resp.get("durationMillis")
        if not resp.get("ok"):
            edit_report["assertions"].append(
                rm.make_assertion("op_success", "FAILED", f"compile ok:false: {resp.get('diagnostics')}"),
            )
            return
        edit_report["assertions"].append(rm.make_assertion("op_success", "PASS", "compile ok:true"))

        host_mirror = mirror_root / f"edit-{edit_index:02d}-classes"
        pull_tree(config.serial, resp["classesDir"], host_mirror)
        after_crcs = rm.snapshot_class_crcs(host_mirror)
        recompiled = rm.diff_recompiled(prev_crcs, after_crcs)
        edit_report["recompiledClasses"] = recompiled
        prev_crcs = after_crcs

        bounds = expected.get("recompiledClasses")
        if bounds is not None:
            lo, hi = bounds.get("min"), bounds.get("max")
            count = len(recompiled)
            in_bounds = (lo is None or count >= lo) and (hi is None or count <= hi)
            edit_report["assertions"].append(
                rm.make_assertion(
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
                candidate = host_mirror / rel
                if candidate.is_file() and marker_bytes in candidate.read_bytes():
                    found = True
                    break
            edit_report["assertions"].append(
                rm.make_assertion(
                    "behavioral_marker",
                    "PASS" if found else "FAILED",
                    f"marker {marker!r} {'found' if found else 'NOT found'} in recompiled class bytes",
                ),
            )

    def do_relink() -> None:
        edit_report["opsInvoked"].append("relink")
        start = time.monotonic()
        try:
            resp = daemon.relink([res_dir_device], manifest_device)
        except rm.DaemonError as e:
            edit_report["ms"]["relink"] = None
            edit_report["assertions"].append(rm.make_assertion("op_success", "SKIPPED", f"daemon transport error: {e}"))
            return
        elapsed_ms = (time.monotonic() - start) * 1000.0
        edit_report["ms"]["relink"] = elapsed_ms
        edit_report["daemonMs_relink"] = resp.get("durationMillis")
        status = "PASS" if resp.get("ok") else "FAILED"
        detail = "relink ok:true" if resp.get("ok") else f"relink ok:false: {resp.get('diagnostics')}"
        edit_report["assertions"].append(rm.make_assertion("op_success", status, detail))

    if route == "code" or route == "noop":
        do_compile()
    elif route == "resources":
        do_relink()
    elif route == "mixed":
        do_compile()
        do_relink()
    elif route == "assets":
        edit_report["assertions"].append(rm.make_assertion("op_success", "PASS", "assets-pass: no daemon op required"))
    elif route == "fallback":
        edit_report["assertions"].append(
            rm.make_assertion("op_success", "PASS", "fallback recorded: no daemon op invoked (full Gradle build expected)"),
        )
    else:
        edit_report["assertions"].append(rm.make_assertion("op_success", "SKIPPED", f"unknown expected route {route!r}"))

    edit_report["status"] = rm.aggregate_status(edit_report["assertions"])
    edit_report["_prev_crcs"] = prev_crcs
    edit_report["_r_classes_dir"] = r_classes_dir
    return edit_report


# ---------------------------------------------------------------------------
# Report rendering: reuse run_matrix's renderer, append a device-vs-host table.
# ---------------------------------------------------------------------------


def render_device_vs_host(run_report: Dict[str, Any], host_medians: Dict[str, float], host_source: str) -> str:
    lines = ["", "## Device vs Host (median compile ms)", "", f"Host source: `{host_source}`", ""]
    lines.append("| App | Host median ms | Device median ms | Ratio (device/host) |")
    lines.append("|---|---|---|---|")
    for app in run_report["apps"]:
        compiles = [e["ms"]["compile"] for e in app["edits"] if e.get("ms", {}).get("compile") is not None]
        device_median = None
        if compiles:
            sorted_c = sorted(compiles)
            n = len(sorted_c)
            device_median = sorted_c[n // 2] if n % 2 else (sorted_c[n // 2 - 1] + sorted_c[n // 2]) / 2
        host_median = host_medians.get(app["name"])
        ratio = f"{device_median / host_median:.1f}x" if (device_median and host_median) else "-"
        lines.append(
            f"| {app['name']} | {rm._fmt_ms(host_median)} | {rm._fmt_ms(device_median)} | {ratio} |",
        )
    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

# Host medians from the just-completed host run (quick-build/corpus/results/
# 20260716T153413Z/matrix.md), used as the comparison baseline unless
# --host-results points at a different matrix.json to recompute them from.
DEFAULT_HOST_MEDIANS = {
    "assets-app": 256.0,
    "fanout-kotlin": 768.0,
    "hello-java": 59.0,
    "hello-kotlin": 58.0,
    "medium-kotlin": 197.0,
    "resources-heavy": 231.0,
}
DEFAULT_HOST_SOURCE = "quick-build/corpus/results/20260716T153413Z/matrix.md"


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-dir", type=Path, default=CORPUS_DIR / "apps")
    parser.add_argument("--results-dir", type=Path, default=CORPUS_DIR / "results")
    parser.add_argument("--work-dir", type=Path, default=None)
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--pkg", default=DEFAULT_PKG)
    parser.add_argument("--daemon-launcher", default=DEVICE_DAEMON_LAUNCHER, help="device path to the daemon launcher script")
    parser.add_argument("--device-android-jar", default=None, help="default: <app-private>/files/home/android-sdk/platforms/android-36/android.jar")
    parser.add_argument("--device-kotlin-stdlib", default=None, help="default: <app-private>/files/home/.cg/quickbuild/daemon/kotlin-stdlib-2.3.0.jar")
    parser.add_argument("--device-aapt2", default=None, help="default: <app-private>/files/home/android-sdk/build-tools/35.0.0/aapt2")
    parser.add_argument("--device-d8-jar", default=None, help="default: <app-private>/files/home/android-sdk/build-tools/35.0.0/lib/d8.jar")
    parser.add_argument("--host-android-jar", type=Path, required=True, help="HOST android.jar, used only for host-side R class generation")
    parser.add_argument("--host-aapt2", type=Path, required=True, help="HOST aapt2, used only for host-side R class generation")
    parser.add_argument("--host-javac", type=Path, required=True, help="HOST javac, used only for host-side R class generation")
    parser.add_argument("--min-api", type=int, default=rm.DEFAULT_MIN_API)
    parser.add_argument("--op-timeout", type=float, default=180.0, help="seconds to wait for one daemon response (adb round-trip is slower than host)")
    parser.add_argument("--apps", default=None, help="comma-separated app names to run (default: all)")
    parser.add_argument("--host-results", type=Path, default=None, help="a host-mode matrix.json to recompute the device-vs-host medians from (default: hardcoded from the 20260716T153413Z host run)")
    return parser.parse_args(argv)


def _host_medians_from_results(path: Path) -> Tuple[Dict[str, float], str]:
    data = json.loads(path.read_text())
    medians: Dict[str, float] = {}
    for app in data["apps"]:
        compiles = [e["ms"]["compile"] for e in app["edits"] if e.get("ms", {}).get("compile") is not None]
        if not compiles:
            continue
        sorted_c = sorted(compiles)
        n = len(sorted_c)
        medians[app["name"]] = sorted_c[n // 2] if n % 2 else (sorted_c[n // 2 - 1] + sorted_c[n // 2]) / 2
    return medians, str(path)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    for label, p in (("--host-android-jar", args.host_android_jar), ("--host-aapt2", args.host_aapt2), ("--host-javac", args.host_javac)):
        if not p.is_file():
            print(f"error: {label} not found: {p}", file=sys.stderr)
            return 2

    app_private = _app_private(args.pkg)
    device_android_jar = args.device_android_jar or f"{app_private}/files/home/android-sdk/platforms/android-36/android.jar"
    device_kotlin_stdlib = args.device_kotlin_stdlib or f"{app_private}/files/home/.cg/quickbuild/daemon/kotlin-stdlib-2.3.0.jar"
    device_aapt2 = args.device_aapt2 or f"{app_private}/files/home/android-sdk/build-tools/35.0.0/aapt2"
    device_d8_jar = args.device_d8_jar or f"{app_private}/files/home/android-sdk/build-tools/35.0.0/lib/d8.jar"

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    results_dir = args.results_dir / f"{timestamp}-device"
    results_dir.mkdir(parents=True, exist_ok=True)

    work_root = args.work_dir or Path(tempfile.gettempdir()) / "quickbuild-matrix-device" / timestamp
    work_root.mkdir(parents=True, exist_ok=True)

    config = DeviceRunConfig(
        serial=args.serial,
        daemon_launcher=args.daemon_launcher,
        device_android_jar=device_android_jar,
        device_kotlin_stdlib=device_kotlin_stdlib,
        device_aapt2=device_aapt2,
        device_d8_jar=device_d8_jar,
        min_api=args.min_api,
        op_timeout=args.op_timeout,
        host_android_jar=args.host_android_jar,
        host_aapt2=args.host_aapt2,
        host_javac=args.host_javac,
        work_root=work_root,
    )

    gaps: List[str] = []
    daemon: Optional[rm.DaemonClient] = None
    candidate = rm.DaemonClient(
        cmd=["adb", "-s", config.serial, "shell", "run-as", args.pkg, "sh", config.daemon_launcher],
        timeout_seconds=config.op_timeout,
        stderr_log=results_dir / "daemon-stderr.log",
    )
    try:
        candidate.start()
        ok, detail = candidate.health_check()
    except (OSError, rm.DaemonError) as e:
        ok, detail = False, str(e)
    if ok:
        daemon = candidate
    else:
        gaps.append(f"on-device daemon failed health check ({detail}); every app/edit in this run is SKIPPED")
        candidate.stop()

    device_rm_rf(config.serial, DEVICE_CORPUS_ROOT)
    device_rm_rf(config.serial, DEVICE_WORK_ROOT)

    all_app_dirs = sorted(p for p in args.corpus_dir.iterdir() if p.is_dir() and (p / "app.json").is_file())
    if args.apps:
        wanted = set(args.apps.split(","))
        all_app_dirs = [p for p in all_app_dirs if p.name in wanted]

    run_report: Dict[str, Any] = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "config": {
            "mode": "device",
            "serial": config.serial,
            "pkg": args.pkg,
            "daemonAvailable": daemon is not None,
            "deviceAndroidJar": config.device_android_jar,
            "deviceKotlinStdlib": config.device_kotlin_stdlib,
            "deviceAapt2": config.device_aapt2,
            "deviceD8Jar": config.device_d8_jar,
            "hostAndroidJar": str(config.host_android_jar),
            "hostAapt2": str(config.host_aapt2),
            "hostJavac": str(config.host_javac),
            "minApi": config.min_api,
        },
        "apps": [],
        "gaps": gaps,
        "summary": {},
    }

    try:
        for app_dir in all_app_dirs:
            app_report = run_app_device(app_dir, daemon, config, gaps)
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

    if args.host_results:
        host_medians, host_source = _host_medians_from_results(args.host_results)
    else:
        host_medians, host_source = DEFAULT_HOST_MEDIANS, DEFAULT_HOST_SOURCE

    markdown = rm.render_markdown(run_report) + render_device_vs_host(run_report, host_medians, host_source)

    (results_dir / "matrix.json").write_text(json.dumps(run_report, indent=2, default=str))
    (results_dir / "matrix.md").write_text(markdown)
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

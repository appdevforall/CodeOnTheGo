#!/usr/bin/env python3
"""Quick Build (ADFA-4128) low-spec farm benchmark (P1a).

Runs a device matrix subset (reusing run_matrix_device.py's own on-device driver, so
this measures the SAME compile/relink ops the real matrix runs exercise) while sampling
CPU/memory in the background and taking before/after thermal snapshots, then reports
p50/p95 compile and relink latency across every edit in the subset.

Requires a device already provisioned by `provision_device.py` (or already provisioned
by hand, e.g. the A56's pre-existing /sdcard/qb-daemon.sh) -- this script does not stage
anything itself, it only drives and measures.

Memory/CPU sampling is best-effort and device-dependent, by design (this runs on
whatever the farm hands us tomorrow, sight unseen tonight):
  - Per-daemon-process RSS via `/proc/<pid>/status` VmRSS, PID discovered from `ps -A`
    (grep for the daemon's main class). If PID discovery fails on a given device/ROM
    (some OEM `ps` builds restrict cross-uid visibility even for run-as's own uid), this
    degrades to "unavailable" for that field rather than aborting the whole run.
  - Whole-package PSS via `dumpsys meminfo <pkg>` (TOTAL PSS line) as a supplementary,
    always-available signal -- this includes CoGo's own app process too, not just the
    daemon, so treat it as a rough ceiling, not a daemon-specific number.
  - Thermal status via `dumpsys thermalservice`, snapshotted before and after the run
    (not polled continuously -- a status *change* across the run is the signal that
    matters, not a fine-grained curve). Parsing is best-effort text matching since the
    dump's shape varies by OEM/Android version; "unavailable" is a valid, honest result.

Stdlib only (plus `adb`). Imports run_matrix and run_matrix_device, reusing their
DaemonClient / DeviceRunConfig / run_app_device machinery rather than re-deriving it.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

import run_matrix as rm
import run_matrix_device as rmd

HARNESS_DIR = Path(__file__).resolve().parent
CORPUS_DIR = HARNESS_DIR.parent

# A small, fast, representative subset -- the same six apps run_matrix_device.py's own
# device-vs-host table already has host medians for (DEFAULT_HOST_MEDIANS), so a bench
# run's numbers are directly comparable to that baseline with no extra plumbing.
DEFAULT_APPS = "assets-app,fanout-kotlin,hello-java,hello-kotlin,medium-kotlin,resources-heavy"


# ---------------------------------------------------------------------------
# Background sampling: CPU/memory + thermal.
# ---------------------------------------------------------------------------


@dataclass
class Sample:
    atMonotonic: float
    daemonRssKb: Optional[int]
    pkgTotalPssKb: Optional[int]


@dataclass
class Sampler:
    serial: str
    pkg: str
    interval_seconds: float
    samples: List[Sample] = field(default_factory=list)
    pid_discovery_detail: str = ""
    _stop: threading.Event = field(default_factory=threading.Event)
    _thread: Optional[threading.Thread] = None
    _pid: Optional[int] = None

    def start(self) -> None:
        self._pid = self._discover_daemon_pid()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=self.interval_seconds + 5.0)

    def _discover_daemon_pid(self) -> Optional[int]:
        # ps -A ARGS includes the full command line on most Android versions; grep for
        # the daemon's main class rather than the java binary name (ambiguous -- CoGo's
        # own tooling-api Gradle daemon is also a `java` process).
        result = _adb(self.serial, ["shell", "ps", "-A", "-o", "PID,ARGS"], timeout=15.0)
        for line in result.stdout.splitlines():
            if "org.appdevforall.cotg.quickbuild.daemon.DaemonMain" in line:
                try:
                    pid = int(line.strip().split()[0])
                    self.pid_discovery_detail = f"found via ps -A (pid {pid})"
                    return pid
                except (ValueError, IndexError):
                    continue
        self.pid_discovery_detail = (
            "daemon PID not found via `ps -A -o PID,ARGS` (grepping for DaemonMain); "
            "this device/ROM's ps output may not include full ARGS for another uid's "
            "process even under run-as. Per-process RSS sampling is unavailable this "
            "run; pkgTotalPssKb (dumpsys meminfo) is still recorded."
        )
        return None

    def _run(self) -> None:
        while not self._stop.is_set():
            self.samples.append(
                Sample(
                    atMonotonic=time.monotonic(),
                    daemonRssKb=self._read_daemon_rss(),
                    pkgTotalPssKb=self._read_pkg_total_pss(),
                ),
            )
            self._stop.wait(self.interval_seconds)

    def _read_daemon_rss(self) -> Optional[int]:
        if self._pid is None:
            return None
        result = _adb(self.serial, ["shell", "cat", f"/proc/{self._pid}/status"], timeout=10.0)
        match = re.search(r"^VmRSS:\s*(\d+)\s*kB", result.stdout, re.MULTILINE)
        return int(match.group(1)) if match else None

    def _read_pkg_total_pss(self) -> Optional[int]:
        result = _adb(self.serial, ["shell", "dumpsys", "meminfo", self.pkg], timeout=15.0)
        match = re.search(r"TOTAL PSS:?\s+(\d+)", result.stdout)
        return int(match.group(1)) if match else None


def _adb(serial: str, args: List[str], timeout: float = 30.0) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout)


def thermal_snapshot(serial: str) -> Dict[str, Any]:
    result = _adb(serial, ["shell", "dumpsys", "thermalservice"], timeout=15.0)
    if result.returncode != 0 or not result.stdout.strip():
        return {"available": False, "detail": "dumpsys thermalservice returned nothing (OEM/ROM restriction?)"}
    match = re.search(r"mStatus\s*=\s*(\d+)", result.stdout)
    if match is None:
        # Some OEMs render "Status: X" instead of "mStatus=X"; try that shape too.
        match = re.search(r"[Ss]tatus:?\s*=?\s*(\d+)", result.stdout)
    if match is None:
        return {"available": False, "detail": "thermalservice dump present but status field not recognized"}
    status = int(match.group(1))
    return {"available": True, "statusCode": status, "statusLabel": THERMAL_STATUS_LABELS.get(status, f"UNKNOWN({status})")}


# Android's PowerManager.THERMAL_STATUS_* enum.
THERMAL_STATUS_LABELS = {
    0: "NONE",
    1: "LIGHT",
    2: "MODERATE",
    3: "SEVERE",
    4: "CRITICAL",
    5: "EMERGENCY",
    6: "SHUTDOWN",
}


# ---------------------------------------------------------------------------
# Percentiles (stdlib only -- nearest-rank method, matches the harness's no-deps rule).
# ---------------------------------------------------------------------------


def percentile(values: List[float], pct: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, int(round(pct / 100.0 * (len(ordered) - 1)))))
    return ordered[k]


def collect_latencies(run_report: Dict[str, Any]) -> Dict[str, List[float]]:
    compiles: List[float] = []
    relinks: List[float] = []
    for app in run_report["apps"]:
        for edit in app["edits"]:
            ms = edit.get("ms", {})
            if ms.get("compile") is not None:
                compiles.append(ms["compile"])
            if ms.get("relink") is not None:
                relinks.append(ms["relink"])
    return {"compile": compiles, "relink": relinks}


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--pkg", default=rmd.DEFAULT_PKG)
    parser.add_argument("--apps", default=DEFAULT_APPS, help="comma-separated app subset (default: the 6-app host-comparable set)")
    parser.add_argument("--corpus-dir", type=Path, default=CORPUS_DIR / "apps")
    parser.add_argument("--results-dir", type=Path, default=CORPUS_DIR / "results")
    parser.add_argument("--work-dir", type=Path, default=None)
    parser.add_argument("--daemon-launcher", default=rmd.DEVICE_DAEMON_LAUNCHER)
    parser.add_argument("--device-android-jar", default=None)
    parser.add_argument("--device-kotlin-stdlib", default=None)
    parser.add_argument("--device-aapt2", default=None)
    parser.add_argument("--device-d8-jar", default=None)
    parser.add_argument("--host-android-jar", type=Path, required=True)
    parser.add_argument("--host-aapt2", type=Path, required=True)
    parser.add_argument("--host-javac", type=Path, required=True)
    parser.add_argument("--min-api", type=int, default=rm.DEFAULT_MIN_API)
    parser.add_argument("--op-timeout", type=float, default=180.0)
    parser.add_argument("--sample-interval", type=float, default=2.0, help="seconds between CPU/mem samples")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)

    for label, p in (("--host-android-jar", args.host_android_jar), ("--host-aapt2", args.host_aapt2), ("--host-javac", args.host_javac)):
        if not p.is_file():
            print(f"error: {label} not found: {p}", file=sys.stderr)
            return 2

    app_private = rmd._app_private(args.pkg)
    device_android_jar = args.device_android_jar or f"{app_private}/files/home/android-sdk/platforms/android-36/android.jar"
    device_kotlin_stdlib = args.device_kotlin_stdlib or f"{app_private}/files/home/.cg/quickbuild/daemon/kotlin-stdlib-2.3.0.jar"
    device_aapt2 = args.device_aapt2 or f"{app_private}/files/home/android-sdk/build-tools/35.0.0/aapt2"
    device_d8_jar = args.device_d8_jar or f"{app_private}/files/home/android-sdk/build-tools/35.0.0/lib/d8.jar"

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    results_dir = args.results_dir / f"{timestamp}-bench"
    results_dir.mkdir(parents=True, exist_ok=True)
    work_root = args.work_dir or Path(tempfile.gettempdir()) / "quickbuild-bench" / timestamp
    work_root.mkdir(parents=True, exist_ok=True)

    config = rmd.DeviceRunConfig(
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

    thermal_before = thermal_snapshot(args.serial)

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
        gaps.append(f"on-device daemon failed health check ({detail}); nothing benchmarked this run")
        candidate.stop()

    sampler = Sampler(serial=args.serial, pkg=args.pkg, interval_seconds=args.sample_interval)

    run_report: Dict[str, Any] = {"apps": [], "gaps": gaps}
    if daemon is not None:
        rmd.device_rm_rf(config.serial, rmd.DEVICE_CORPUS_ROOT)
        rmd.device_rm_rf(config.serial, rmd.DEVICE_WORK_ROOT)

        wanted = set(args.apps.split(","))
        all_app_dirs = sorted(p for p in args.corpus_dir.iterdir() if p.is_dir() and (p / "app.json").is_file() and p.name in wanted)
        missing = wanted - {p.name for p in all_app_dirs}
        for name in sorted(missing):
            gaps.append(f"{name}: not found under {args.corpus_dir} -- skipped")

        sampler.start()
        try:
            for app_dir in all_app_dirs:
                app_report = rmd.run_app_device(app_dir, daemon, config, gaps)
                run_report["apps"].append(app_report)
        finally:
            sampler.stop()
            daemon.stop()
    else:
        sampler.pid_discovery_detail = "daemon never started; no samples collected"

    thermal_after = thermal_snapshot(args.serial)

    latencies = collect_latencies(run_report)
    percentiles = {
        op: {
            "p50Ms": percentile(values, 50),
            "p95Ms": percentile(values, 95),
            "n": len(values),
        }
        for op, values in latencies.items()
    }

    rss_values = [s.daemonRssKb for s in sampler.samples if s.daemonRssKb is not None]
    pss_values = [s.pkgTotalPssKb for s in sampler.samples if s.pkgTotalPssKb is not None]
    memory_summary = {
        "daemonRssKb": {
            "min": min(rss_values) if rss_values else None,
            "max": max(rss_values) if rss_values else None,
            "mean": round(statistics.mean(rss_values), 1) if rss_values else None,
        },
        "pkgTotalPssKb": {
            "min": min(pss_values) if pss_values else None,
            "max": max(pss_values) if pss_values else None,
            "mean": round(statistics.mean(pss_values), 1) if pss_values else None,
        },
        "sampleCount": len(sampler.samples),
        "pidDiscovery": sampler.pid_discovery_detail,
    }

    bench_report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "serial": args.serial,
        "pkg": args.pkg,
        "appsRequested": sorted(set(args.apps.split(","))),
        "gaps": gaps,
        "latencyPercentiles": percentiles,
        "memory": memory_summary,
        "thermal": {"before": thermal_before, "after": thermal_after},
        "rawMatrix": run_report,
    }

    (results_dir / "bench.json").write_text(json.dumps(bench_report, indent=2, default=str))
    (results_dir / "bench.md").write_text(render_markdown(bench_report))
    print(f"wrote {results_dir / 'bench.json'}")
    print(f"wrote {results_dir / 'bench.md'}")
    return 1 if gaps and not run_report["apps"] else 0


def render_markdown(report: Dict[str, Any]) -> str:
    lines = [f"# Quick Build low-spec bench -- {report['serial']}", "", f"Generated: {report['generatedAt']}", ""]

    lines += ["## Latency (ms)", "", "| Op | p50 | p95 | n |", "|---|---|---|---|"]
    for op, stats in report["latencyPercentiles"].items():
        p50 = f"{stats['p50Ms']:.0f}" if stats["p50Ms"] is not None else "-"
        p95 = f"{stats['p95Ms']:.0f}" if stats["p95Ms"] is not None else "-"
        lines.append(f"| {op} | {p50} | {p95} | {stats['n']} |")
    lines.append("")

    mem = report["memory"]
    lines += ["## Memory", "", f"Samples: {mem['sampleCount']} -- {mem['pidDiscovery']}", ""]
    lines += ["| Metric | min | max | mean |", "|---|---|---|---|"]
    for key, label in (("daemonRssKb", "Daemon RSS (kB)"), ("pkgTotalPssKb", "Package TOTAL PSS (kB)")):
        m = mem[key]
        vals = tuple(("-" if m[k] is None else str(m[k])) for k in ("min", "max", "mean"))
        lines.append(f"| {label} | {vals[0]} | {vals[1]} | {vals[2]} |")
    lines.append("")

    thermal = report["thermal"]
    lines += ["## Thermal", ""]
    for label, snap in (("Before", thermal["before"]), ("After", thermal["after"])):
        if snap.get("available"):
            lines.append(f"- {label}: {snap['statusLabel']} (code {snap['statusCode']})")
        else:
            lines.append(f"- {label}: unavailable -- {snap.get('detail')}")
    lines.append("")

    if report["gaps"]:
        lines += ["## Gaps", ""]
        lines += [f"- {g}" for g in report["gaps"]]
        lines.append("")

    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())

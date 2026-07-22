#!/usr/bin/env python3
"""Quick Build (ADFA-4128) low-spec farm prep -- ON-DEVICE provisioning (P1a).

Makes an arbitrary Android device ready for `run_matrix_device.py` / `bench_device.py`:
stages the compile daemon's jars into CoGo's app-private storage and writes the
`/sdcard/qb-daemon.sh` launcher those two scripts spawn via `run-as`. This is the
missing setup step `run_matrix_device.py`'s docstring assumes already happened -- see
that module's docstring for the on-device layout this script reproduces.

What this script does NOT do: install CoGo, or run CoGo's own first-launch Termux/SDK
bootstrap (the JDK, the Android SDK, aapt2/d8 -- all bundled in the APK's assets and
unpacked by the app itself on first launch, entirely unrelated to Quick Build). Those
are a hard prerequisite this script only VERIFIES; if they are missing, it fails loudly
with the fix ("launch CoGo once, let first-run provisioning finish") rather than trying
to reproduce a bootstrap that needs the running app.

What it DOES stage: the daemon jar + its runtime classpath (the `:quickbuild-daemon:
stageDaemon` Gradle task's output -- run that task first if `--daemon-dir` doesn't
exist) into `<app-private>/files/home/.cg/quickbuild/daemon/` (the same location
`QuickBuildArtifactStager` extracts to from APK assets on a real device, so this
script is staging the SAME artifacts through a different door for a device we cannot
drive interactively). Then it writes the on-device launcher and verifies the whole
chain with a real `ping` round-trip through the same `DaemonClient` protocol class
`run_matrix_device.py` uses.

App-private storage isn't writable by plain `adb push`; the two-hop idiom here (push to
a shared `/data/local/tmp` staging dir, then `run-as` copies it into the app's private
dir) is the standard way around that without root.

Idempotent: safe to re-run against a device that already has some/all of this staged --
every step overwrites rather than assumes absence, and the final health check is the
single source of truth for "did this work," not any individual step's exit code.

Stdlib only (plus a plain `adb` binary on PATH). Imports run_matrix for DaemonClient,
reusing the exact protocol class the matrix runners use for a real ping health check.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List, Optional

import run_matrix as rm

HARNESS_DIR = Path(__file__).resolve().parent
CORPUS_DIR = HARNESS_DIR.parent
QUICK_BUILD_DIR = CORPUS_DIR.parent
REPO_ROOT = QUICK_BUILD_DIR.parent

DEFAULT_PKG = "com.itsaky.androidide"
DEFAULT_DAEMON_STAGE_DIR = REPO_ROOT / "quickbuild-daemon" / "build" / "daemon"
DEVICE_DAEMON_LAUNCHER = "/sdcard/qb-daemon.sh"
DEVICE_STAGE_TMP = "/data/local/tmp/qb-daemon-stage"

# Defaults match run_matrix_device.py's own defaults -- both scripts describe the
# same on-device layout. Override via flags if a device pins a different SDK level.
DEFAULT_PLATFORM = "android-36"
DEFAULT_BUILD_TOOLS = "35.0.0"


class ProvisionError(RuntimeError):
    """A prerequisite is missing or a step failed; carries the actionable fix."""


def _app_private(pkg: str) -> str:
    return f"/data/user/0/{pkg}"


def _run_adb(serial: str, args: List[str], timeout: float = 60.0) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout)


def _run_as(serial: str, pkg: str, shell_cmd: str, timeout: float = 60.0) -> subprocess.CompletedProcess:
    return _run_adb(serial, ["shell", "run-as", pkg, "sh", "-c", shell_cmd], timeout=timeout)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ProvisionError(message)


def check_device_reachable(serial: str) -> None:
    result = _run_adb(serial, ["get-state"])
    _require(
        result.returncode == 0 and result.stdout.strip() == "device",
        f"adb cannot reach serial '{serial}' (get-state: {result.stdout.strip() or result.stderr.strip()}). "
        "Is the device connected and authorized (`adb devices -l`)?",
    )


def check_cogo_installed(serial: str, pkg: str) -> None:
    result = _run_adb(serial, ["shell", "pm", "list", "packages", pkg])
    _require(
        pkg in result.stdout,
        f"CoGo ('{pkg}') is not installed on {serial}. Install the APK before provisioning Quick Build.",
    )


def _glob_one(serial: str, pkg: str, parent_dir: str, prefix: str, what: str) -> str:
    """One `run-as ls` to pick the single expected `prefix*` child of parent_dir -- the
    JDK/SDK directory names are versioned and can differ across CoGo builds/devices."""
    result = _run_as(serial, pkg, f"ls {parent_dir}")
    _require(
        result.returncode == 0,
        f"could not list {parent_dir} inside CoGo's app-private storage: {result.stderr.strip()}. "
        f"Has CoGo finished its own bootstrap (JDK/SDK unpack) on this device?",
    )
    candidates = [line.strip() for line in result.stdout.splitlines() if line.strip().startswith(prefix)]
    _require(
        len(candidates) > 0,
        f"no {what} found under {parent_dir} (looked for a '{prefix}*' entry). "
        "Launch CoGo once and let its first-run bootstrap finish, then re-run this script.",
    )
    return candidates[0]


def discover_bootstrap_paths(
    serial: str,
    pkg: str,
    platform: str,
    build_tools: str,
) -> "BootstrapPaths":
    app_private = _app_private(pkg)
    jdk_dir = _glob_one(serial, pkg, f"{app_private}/files/usr/lib/jvm", "java-", "a bundled JDK")
    java_binary = f"{app_private}/files/usr/lib/jvm/{jdk_dir}/bin/java"
    android_jar = f"{app_private}/files/home/android-sdk/platforms/{platform}/android.jar"
    aapt2 = f"{app_private}/files/home/android-sdk/build-tools/{build_tools}/aapt2"
    d8_jar = f"{app_private}/files/home/android-sdk/build-tools/{build_tools}/lib/d8.jar"
    daemon_dir = f"{app_private}/files/home/.cg/quickbuild/daemon"

    for label, path in (
        ("java binary", java_binary),
        ("android.jar", android_jar),
        ("aapt2", aapt2),
        ("d8.jar", d8_jar),
    ):
        result = _run_as(serial, pkg, f"test -e {path}")
        _require(
            result.returncode == 0,
            f"{label} not found at {path}. CoGo has not finished its own Termux/SDK bootstrap "
            "on this device -- launch the app once, wait for first-run provisioning to complete, "
            "then re-run this script (this is CoGo's own bootstrap, not something Quick Build stages).",
        )

    return BootstrapPaths(
        java_binary=java_binary,
        android_jar=android_jar,
        aapt2=aapt2,
        d8_jar=d8_jar,
        daemon_dir=daemon_dir,
    )


class BootstrapPaths:
    def __init__(self, java_binary: str, android_jar: str, aapt2: str, d8_jar: str, daemon_dir: str) -> None:
        self.java_binary = java_binary
        self.android_jar = android_jar
        self.aapt2 = aapt2
        self.d8_jar = d8_jar
        self.daemon_dir = daemon_dir


def check_daemon_stage_dir(daemon_stage_dir: Path) -> List[Path]:
    _require(
        daemon_stage_dir.is_dir(),
        f"{daemon_stage_dir} does not exist. Build it first: "
        "`flox activate -d flox/local -- ./gradlew :quickbuild-daemon:stageDaemon`.",
    )
    jars = sorted(daemon_stage_dir.glob("*.jar"))
    _require(
        any(j.name == "quickbuild-daemon.jar" for j in jars),
        f"{daemon_stage_dir} has no quickbuild-daemon.jar -- re-run "
        "`:quickbuild-daemon:stageDaemon` and check its output.",
    )
    return jars


def push_daemon_jars(serial: str, pkg: str, daemon_stage_dir: Path, daemon_dir: str) -> int:
    """Two-hop copy: app-private storage isn't `adb push`-writable directly, so this
    pushes to a shared /data/local/tmp staging dir, then `run-as cp`s it into place."""
    _run_adb(serial, ["shell", "rm", "-rf", DEVICE_STAGE_TMP])
    push = _run_adb(serial, ["push", str(daemon_stage_dir), DEVICE_STAGE_TMP], timeout=180.0)
    _require(push.returncode == 0, f"adb push of {daemon_stage_dir} failed: {push.stderr.strip()}")

    copy = _run_as(
        serial,
        pkg,
        f"mkdir -p {daemon_dir} && rm -f {daemon_dir}/*.jar && cp {DEVICE_STAGE_TMP}/*.jar {daemon_dir}/",
        timeout=120.0,
    )
    _require(copy.returncode == 0, f"staging the daemon jars into {daemon_dir} failed: {copy.stderr.strip()}")
    _run_adb(serial, ["shell", "rm", "-rf", DEVICE_STAGE_TMP])

    count = _run_as(serial, pkg, f"ls {daemon_dir}/*.jar | wc -l")
    return int(count.stdout.strip() or "0")


def write_launcher(serial: str, pkg: str, paths: BootstrapPaths) -> None:
    app_private = _app_private(pkg)
    script = f"""#!/system/bin/sh
# On-device stdio-protocol launcher for the quickbuild daemon (test harness).
# Regenerated by provision_device.py -- do not hand-edit, re-run the script instead.
BASE={app_private}
JAVA="{paths.java_binary}"
DAEMON="{paths.daemon_dir}"
CP=""
for j in "$DAEMON"/*.jar; do CP="$CP:$j"; done
CP="${{CP#:}}"
exec "$JAVA" -Xmx512m --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED -cp "$CP" org.appdevforall.cotg.quickbuild.daemon.DaemonMain
"""
    with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as f:
        f.write(script)
        local_path = f.name
    try:
        push = _run_adb(serial, ["push", local_path, DEVICE_DAEMON_LAUNCHER])
        _require(push.returncode == 0, f"pushing the launcher failed: {push.stderr.strip()}")
    finally:
        Path(local_path).unlink(missing_ok=True)


def health_check(serial: str, pkg: str) -> None:
    client = rm.DaemonClient(cmd=["adb", "-s", serial, "shell", "run-as", pkg, "sh", DEVICE_DAEMON_LAUNCHER])
    client.start()
    try:
        ok, detail = client.health_check()
        _require(ok, f"daemon health check failed after provisioning: {detail}")
    finally:
        client.stop()


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="adb serial of the farm device to provision")
    parser.add_argument("--pkg", default=DEFAULT_PKG)
    parser.add_argument(
        "--daemon-dir",
        type=Path,
        default=DEFAULT_DAEMON_STAGE_DIR,
        help="HOST path to the built daemon (`:quickbuild-daemon:stageDaemon` output)",
    )
    parser.add_argument("--platform", default=DEFAULT_PLATFORM, help="android-sdk platforms/ dir name")
    parser.add_argument("--build-tools", default=DEFAULT_BUILD_TOOLS, help="android-sdk build-tools/ dir name")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    try:
        print(f"[1/6] checking {args.serial} is reachable...")
        check_device_reachable(args.serial)

        print(f"[2/6] checking CoGo ({args.pkg}) is installed...")
        check_cogo_installed(args.serial, args.pkg)

        print("[3/6] checking CoGo's own JDK/SDK bootstrap is complete...")
        paths = discover_bootstrap_paths(args.serial, args.pkg, args.platform, args.build_tools)
        print(f"      java: {paths.java_binary}")
        print(f"      android.jar: {paths.android_jar}")
        print(f"      aapt2: {paths.aapt2}")
        print(f"      d8.jar: {paths.d8_jar}")

        print(f"[4/6] checking the daemon build at {args.daemon_dir}...")
        jars = check_daemon_stage_dir(args.daemon_dir)
        print(f"      {len(jars)} jars to stage")

        print(f"[5/6] staging the daemon jars into {paths.daemon_dir}...")
        staged_count = push_daemon_jars(args.serial, args.pkg, args.daemon_dir, paths.daemon_dir)
        _require(
            staged_count == len(jars),
            f"staged {staged_count} jars on-device but expected {len(jars)} -- push may have partially failed",
        )
        print(f"      {staged_count} jars staged")

        print(f"[6/6] writing the launcher + verifying with a ping health check...")
        write_launcher(args.serial, args.pkg, paths)
        health_check(args.serial, args.pkg)

        print(f"OK: {args.serial} is provisioned for Quick Build device runs.")
        print(f"    run_matrix_device.py / bench_device.py --serial {args.serial} --pkg {args.pkg}")
        return 0
    except ProvisionError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

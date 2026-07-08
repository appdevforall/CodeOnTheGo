#!/usr/bin/env python3
"""Mini-Stubby phase-3 stress harness (ADFA-4128, component F).

Generates a synthetic payload overlay — N extra classes with K methods each,
plus M extra string resources — into a SCRATCH COPY of the payload at
    build/stress-payload/
The real payload/ directory is NEVER modified (hard-asserted below).

The scratch copy has the exact payload layout (java/ res/ assets/
AndroidManifest.xml), so it can be built two ways:

  1. By this script (default): the same aapt2 -> javac -> d8 -> zip pipeline the
     spike uses everywhere, with the toolchain from tools/env.sh. Emits a
     summary line (classes / approx methods / apk bytes / dex count) and
     per-stage build times. Output apk: build/stress-out/payload-stress-<label>.apk
  2. By the devloop daemon (pass --no-build here), pointed at the scratch copy:
         sh devloop/run_devloop.sh --payload build/stress-payload [--dry-run]
     The daemon watches the scratch tree, so re-running this script at a new
     size triggers an automatic warm rebuild (+ deploy unless --dry-run).
     NOTE: the daemon may start a build mid-generation (80 ms debounce vs many
     file writes); a broken intermediate build is logged and superseded by the
     final one. Trust the LAST "DEVLOOP save->deployed" line, or POST /rebuild.

THE MULTIDEX KNOB: a dex file holds at most 65,536 method references. Every
generated method (plus each class's <init>) is one method def, so
    total method defs  ~=  classes x (methods_per_class + 1)
Push that product past 65,536 and d8 (running with --min-api 30 = native
multidex) MUST spill into classes2.dex. The XL preset (700 x 120 ~= 84,700)
forces multidex; L (400 x 120 ~= 48,400) deliberately stays single-dex.

Runtime proof, not just packaging proof: the scratch copy's Main.render() gets
a one-line injected call to app.payload.stress.StressTouch.touch(), which
Class.forName()-loads a deterministic sample of the generated classes (always
including the first and the LAST — on a multidex payload the last-named classes
land in classes2.dex) and logs the result to logcat tag "MiniStubbyStress".
If Main.java's render() can't be found by the injector (e.g. Component D
restructured it), a warning is printed and the run continues — packaging is
still stress-tested, only the runtime class-load proof is skipped.

Usage:
    python3 stress/gen_stress.py --size S|M|L|XL          # generate + build
    python3 stress/gen_stress.py --size XL --no-build     # generate only (daemon builds)
    python3 stress/gen_stress.py --classes 700 --methods 120 --strings 6000
    python3 stress/gen_stress.py --size M --touch all     # Class.forName ALL classes

Presets:
    S  :  25 classes x  20 methods,  100 strings   (~   525 method defs)
    M  : 150 classes x  60 methods, 1000 strings   (~ 9,150 method defs)
    L  : 400 classes x 120 methods, 3000 strings   (~48,400 defs, single dex)
    XL : 700 classes x 120 methods, 6000 strings   (~84,700 defs, MULTIDEX)
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

SPIKE_ROOT = Path(__file__).resolve().parent.parent
PAYLOAD_DIR = SPIKE_ROOT / "payload"
SCRATCH_DIR = SPIKE_ROOT / "build" / "stress-payload"
OUT_DIR = SPIKE_ROOT / "build" / "stress-out"
STRESS_PKG_DIR = Path("java/app/payload/stress")  # relative to scratch root
STRESS_STRINGS = Path("res/values/stress_strings.xml")

PRESETS = {
    "S": (25, 20, 100),
    "M": (150, 60, 1000),
    "L": (400, 120, 3000),
    "XL": (700, 120, 6000),
}

DEX_METHOD_LIMIT = 65536


def load_env():
    """Source tools/env.sh in a subshell and pull out the toolchain paths."""
    env_sh = SPIKE_ROOT / "tools" / "env.sh"
    r = subprocess.run(
        ["sh", "-c", '. "$0" >/dev/null 2>&1; printf "%s\\n%s\\n%s\\n" '
                     '"$JAVA_HOME" "$BT" "$PLATFORM"', str(env_sh)],
        capture_output=True, text=True, check=True)
    java_home, bt, platform = r.stdout.splitlines()[:3]
    for label, p in (("JAVA_HOME", java_home), ("build-tools", bt), ("android.jar", platform)):
        if not p or not Path(p).exists():
            sys.exit(f"gen_stress: {label} missing ({p!r}) — check tools/env.sh")
    return java_home, bt, platform


def write_if_changed(path: Path, content: str) -> bool:
    """Write only when content differs — keeps devloop watcher churn minimal."""
    if path.exists() and path.read_text(encoding="utf-8") == content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return True


def refresh_base_copy():
    """Copy payload/ over the scratch tree IN PLACE (no rm -rf of the roots —
    deleting java/ res/ assets/ would invalidate a running daemon's watch keys).
    Files that disappeared from payload/ are removed from the scratch copy too
    (stale sources/resources would poison javac/aapt2); generated stress
    artifacts are kept, and directories are left in place (watcher-friendly)."""
    assert SCRATCH_DIR != PAYLOAD_DIR and PAYLOAD_DIR not in SCRATCH_DIR.parents
    copied = 0
    base_files = set()
    for src in sorted(PAYLOAD_DIR.rglob("*")):
        if src.is_dir():
            continue
        rel = src.relative_to(PAYLOAD_DIR)
        base_files.add(rel)
        dst = SCRATCH_DIR / rel
        data = src.read_bytes()
        if dst.exists() and dst.read_bytes() == data:
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(data)
        copied += 1
    if SCRATCH_DIR.exists():
        stress_root = SCRATCH_DIR / STRESS_PKG_DIR
        for f in sorted(SCRATCH_DIR.rglob("*")):
            if f.is_dir() or stress_root in f.parents:
                continue
            rel = f.relative_to(SCRATCH_DIR)
            if rel not in base_files and rel != STRESS_STRINGS:
                f.unlink()
    return copied


def gen_class(i: int, methods: int) -> str:
    lines = [
        "package app.payload.stress;",
        "",
        "/** Generated by stress/gen_stress.py — do not edit. */",
        f"public final class C{i:05d} {{",
    ]
    for m in range(methods):
        lines.append(f"    public int m{m}(int x) {{ return x * 31 + {i * 7 + m}; }}")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def gen_touch(total: int, stride: int) -> str:
    return f"""package app.payload.stress;

/**
 * Generated by stress/gen_stress.py — do not edit.
 * Class-loads a deterministic sample of the {total} generated classes (always
 * including the first and the LAST, which on a multidex payload sits in
 * classes2.dex) and logs to logcat tag MiniStubbyStress. Never throws.
 */
public final class StressTouch {{
    private StressTouch() {{}}

    public static String touch() {{
        long t0 = android.os.SystemClock.uptimeMillis();
        int ok = 0, tried = 0;
        for (int i = 0; i < {total}; i += {stride}) {{ tried++; if (load(i)) ok++; }}
        if (({total} - 1) % {stride} != 0) {{ tried++; if (load({total} - 1)) ok++; }}
        String msg = "stress touch: " + ok + "/" + tried + " sampled classes loaded (of "
                + {total} + " generated) in "
                + (android.os.SystemClock.uptimeMillis() - t0) + " ms";
        try {{ android.util.Log.i("MiniStubbyStress", msg); }} catch (Throwable t) {{ }}
        return msg;
    }}

    private static boolean load(int i) {{
        try {{
            Class.forName(String.format(java.util.Locale.ROOT, "app.payload.stress.C%05d", i));
            return true;
        }} catch (Throwable t) {{
            return false;
        }}
    }}
}}
"""


RENDER_RE = re.compile(
    r"(public\s+static\s+(?:android\.view\.)?View\s+render\s*\([^)]*\)\s*\{)")
INJECT = ("\n        try { app.payload.stress.StressTouch.touch(); }"
          " catch (Throwable stressT) { }  // injected by gen_stress.py\n")


def inject_touch(main_java: Path) -> bool:
    src = main_java.read_text(encoding="utf-8")
    if "StressTouch.touch()" in src:
        return True  # already injected (refresh_base_copy overwrites, so this is rare)
    # Inject into EVERY render(...) overload (phase 3 has render(host) and
    # render(host, savedState)); touch() is cheap when classes are already loaded.
    new, n = RENDER_RE.subn(lambda m: m.group(1) + INJECT, src)
    if n == 0:
        return False
    main_java.write_text(new, encoding="utf-8")
    return True


def generate(classes: int, methods: int, strings: int, touch: str):
    refresh_base_copy()

    # Remove stress classes left over from a previous LARGER run (delete files,
    # keep the directory — friendlier to a running daemon's watcher).
    stress_dir = SCRATCH_DIR / STRESS_PKG_DIR
    stress_dir.mkdir(parents=True, exist_ok=True)
    for old in stress_dir.glob("C*.java"):
        idx = int(old.stem[1:])
        if idx >= classes:
            old.unlink()

    for i in range(classes):
        write_if_changed(stress_dir / f"C{i:05d}.java", gen_class(i, methods))

    stride = 1 if touch == "all" else max(1, classes // 16)
    write_if_changed(stress_dir / "StressTouch.java", gen_touch(classes, stride))

    body = ["<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<!-- Generated by stress/gen_stress.py - do not edit. -->",
            "<resources>"]
    for i in range(strings):
        body.append(f'    <string name="stress_s{i:05d}">stress string {i:05d} — '
                    f'synthetic resource payload for reload-latency measurement</string>')
    body.append("</resources>\n")
    write_if_changed(SCRATCH_DIR / STRESS_STRINGS, "\n".join(body))

    touched = False
    if touch != "none":
        touched = inject_touch(SCRATCH_DIR / "java/app/payload/Main.java")
        if not touched:
            print("gen_stress: WARN — could not find 'public static View render(...) {' in "
                  "Main.java; StressTouch call NOT injected (packaging still stressed, "
                  "runtime class-load proof skipped)")
    return touched


def run(cmd, cwd, env, label, timings):
    t0 = time.monotonic()
    r = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    timings[label] = int((time.monotonic() - t0) * 1000)
    if r.returncode != 0:
        sys.exit(f"gen_stress: {label} failed (exit {r.returncode}):\n{r.stdout}\n{r.stderr}")


def build(label: str, java_home: str, bt: str, platform: str):
    shutil.rmtree(OUT_DIR, ignore_errors=True)
    gen = OUT_DIR / "gen"; classes_dir = OUT_DIR / "classes"; dex_dir = OUT_DIR / "dex"
    for d in (gen, classes_dir, dex_dir):
        d.mkdir(parents=True)
    apk = OUT_DIR / f"payload-stress-{label}.apk"
    env = dict(os.environ, JAVA_HOME=java_home,
               PATH=f"{java_home}/bin:{bt}:{os.environ.get('PATH', '')}")
    timings = {}

    res_zip = OUT_DIR / "res.zip"
    run([f"{bt}/aapt2", "compile", "--dir", str(SCRATCH_DIR / "res"), "-o", str(res_zip)],
        SPIKE_ROOT, env, "aapt2-compile", timings)
    link = [f"{bt}/aapt2", "link", "--manifest", str(SCRATCH_DIR / "AndroidManifest.xml"),
            "-I", platform, "--package-id", "0x80",
            "--min-sdk-version", "30", "--target-sdk-version", "34",
            "--java", str(gen)]
    if (SCRATCH_DIR / "assets").is_dir():
        link += ["-A", str(SCRATCH_DIR / "assets")]
    link += ["-o", str(apk), str(res_zip)]
    run(link, SPIKE_ROOT, env, "aapt2-link", timings)
    timings["aapt2"] = timings.pop("aapt2-compile") + timings.pop("aapt2-link")

    sources = OUT_DIR / "sources.txt"
    srcs = sorted(str(p) for root in (SCRATCH_DIR / "java", gen) for p in root.rglob("*.java"))
    sources.write_text("\n".join(srcs) + "\n")
    run([f"{java_home}/bin/javac", "-classpath", platform, "-d", str(classes_dir),
         "-encoding", "UTF-8", "-Xlint:-options", f"@{sources}"],
        SPIKE_ROOT, env, "javac", timings)

    classes_txt = OUT_DIR / "classes.txt"
    cls = sorted(str(p) for p in classes_dir.rglob("*.class"))
    classes_txt.write_text("\n".join(cls) + "\n")
    run([f"{bt}/d8", "--lib", platform, "--min-api", "30",
         "--output", str(dex_dir), f"@{classes_txt}"],
        SPIKE_ROOT, env, "d8", timings)

    dex_files = sorted(p.name for p in dex_dir.glob("*.dex"))
    if not dex_files:
        sys.exit("gen_stress: d8 emitted no .dex files")
    run(["zip", "-q", "-j", str(apk)] + dex_files, dex_dir, env, "pack", timings)

    with zipfile.ZipFile(apk) as z:
        dex_entries = {i.filename: i.file_size for i in z.infolist()
                       if i.filename.endswith(".dex")}
    return apk, apk.stat().st_size, dex_entries, timings


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", choices=PRESETS, help="preset: S, M, L, XL")
    ap.add_argument("--classes", type=int, help="extra classes to generate")
    ap.add_argument("--methods", type=int, help="methods per generated class")
    ap.add_argument("--strings", type=int, help="extra string resources")
    ap.add_argument("--touch", choices=["sample", "all", "none"], default="sample",
                    help="runtime Class.forName coverage of generated classes (default: sample)")
    ap.add_argument("--no-build", action="store_true",
                    help="generate the scratch overlay only (a devloop daemon with "
                         "--payload build/stress-payload does the building)")
    args = ap.parse_args()

    if args.size:
        classes, methods, strings = PRESETS[args.size]
        label = args.size
    else:
        label = "custom"
        classes, methods, strings = 200, 50, 1000
    if args.classes is not None: classes = args.classes
    if args.methods is not None: methods = args.methods
    if args.strings is not None: strings = args.strings

    approx_defs = classes * (methods + 1)
    multidex = "YES (forces classes2.dex)" if approx_defs > DEX_METHOD_LIMIT else "no"
    print(f"STRESS gen: label={label} classes={classes} methodsPerClass={methods} "
          f"strings={strings} approxStressMethodDefs={approx_defs} "
          f"(64k limit {DEX_METHOD_LIMIT}) multidexExpected={multidex}")

    t0 = time.monotonic()
    generate(classes, methods, strings, args.touch)
    gen_ms = int((time.monotonic() - t0) * 1000)
    print(f"STRESS gen: scratch payload ready at {SCRATCH_DIR} ({gen_ms} ms)")

    if args.no_build:
        print("STRESS gen: --no-build — the devloop daemon (run with "
              "--payload build/stress-payload) will pick the change up and rebuild.")
        return

    java_home, bt, platform = load_env()
    apk, apk_bytes, dex_entries, t = build(label, java_home, bt, platform)
    dex_desc = ", ".join(f"{n}:{s}B" for n, s in sorted(dex_entries.items()))
    print(f"STRESS build: aapt2={t['aapt2']}ms javac={t['javac']}ms d8={t['d8']}ms "
          f"pack={t['pack']}ms total={sum(t.values())}ms")
    print(f"STRESS summary: label={label} classes={classes} approxMethodDefs={approx_defs} "
          f"strings={strings} apk={apk} apkBytes={apk_bytes} "
          f"dexCount={len(dex_entries)} dex=[{dex_desc}]")


if __name__ == "__main__":
    main()

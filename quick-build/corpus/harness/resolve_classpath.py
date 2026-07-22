#!/usr/bin/env python3
"""Resolve Maven coordinates to a flat list of compile-classpath jars, for
vendored corpus apps whose real source needs androidx/library jars on
--classpath-extra (SecUSo apps, sora-editor-lib's androidx.annotation, etc.).

Delegates the actual dependency-graph resolution to `resolve-project/` (a
throwaway Gradle project checked in beside this script) via this repo's own
gradlew, so transitive versions come from real Gradle resolution rather than
hand-chasing. AAR artifacts are unzipped once (cached by content hash of the
resolved path) to pull out classes.jar, since --classpath-extra wants plain
jars, not aars.

Usage:
    python3 resolve_classpath.py --coords androidx.appcompat:appcompat:1.6.1,com.google.android.material:material:1.4.0

Prints one resolved classpath jar path per line to stdout. Non-zero exit and
a message on stderr if Gradle resolution fails (e.g. a coordinate typo, or a
version that genuinely isn't published).
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import zipfile
from pathlib import Path

HARNESS_DIR = Path(__file__).resolve().parent
CORPUS_DIR = HARNESS_DIR.parent
REPO_ROOT = CORPUS_DIR.parent.parent
RESOLVE_PROJECT = HARNESS_DIR / "resolve-project"
JAR_CACHE = CORPUS_DIR / ".cache" / "androidx-jars"


def find_flox_java_bin() -> str | None:
    candidates = sorted(REPO_ROOT.glob("flox/local/.flox/run/*/bin/java"))
    return str(candidates[0]) if candidates else None


def resolve(coords: list[str]) -> list[str]:
    gradlew = REPO_ROOT / "gradlew"
    dep_list = ",".join(coords)
    cmd = [str(gradlew), "-p", str(RESOLVE_PROJECT), "resolveClasspath", f"-PdepList={dep_list}", "-q"]
    env_prefix = []
    java_bin = find_flox_java_bin()
    proc = subprocess.run(
        cmd,
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        env={**__import__("os").environ, **({"JAVA_HOME": str(Path(java_bin).parent.parent)} if java_bin else {})},
    )
    if proc.returncode != 0:
        raise SystemExit(f"gradle resolution failed:\n{proc.stdout}\n{proc.stderr}")
    return [line.split("RESOLVED:", 1)[1] for line in proc.stdout.splitlines() if line.startswith("RESOLVED:")]


def extract_classes_jar(aar_path: Path) -> Path:
    digest = hashlib.sha1(str(aar_path).encode()).hexdigest()[:16]
    out = JAR_CACHE / f"{aar_path.stem}-{digest}.jar"
    if out.exists():
        return out
    JAR_CACHE.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aar_path) as z:
        with z.open("classes.jar") as src, out.open("wb") as dst:
            dst.write(src.read())
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--coords", required=True, help="comma-separated group:artifact:version")
    args = parser.parse_args()

    resolved_paths = resolve(args.coords.split(","))
    out_jars: list[str] = []
    for p in resolved_paths:
        path = Path(p)
        if "kotlin-stdlib" in path.name:
            continue  # the daemon's own bundled stdlib is already on the classpath
        if path.suffix == ".aar":
            out_jars.append(str(extract_classes_jar(path)))
        else:
            out_jars.append(str(path))
    for j in out_jars:
        print(j)


if __name__ == "__main__":
    main()

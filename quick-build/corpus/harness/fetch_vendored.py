#!/usr/bin/env python3
"""Materialize vendored corpus apps from their upstream repos, without checking
vendored code into this repo.

An app under corpus/apps/ that carries a vendor.json is a VENDORED app: its
third-party sources are NOT in this repo. vendor.json pins {repo, commit} and
maps upstream paths to app-relative destinations ("files" for individual files,
"trees" for whole directories). This script:

  1. shallow-fetches the pinned commit into corpus/.cache/upstream/<slug>-<sha>/
     (GitHub serves arbitrary-sha shallow fetches; the cache is immutable per sha,
     so this step is skipped when already present),
  2. copies the app dir (scaffolding, manifest, edits, app.json) to
     corpus/.cache/apps/<name>/ and drops the fetched upstream files into their
     destinations,
  3. applies each edits/*/<File>.<ext>.patch (a unified diff against the pinned
     commit) to produce the edit's replacement file next to its meta.json in the
     materialized copy - so run_matrix.py sees an ordinary, complete app dir.

corpus/.cache/ is gitignored; run_matrix.py redirects vendored apps there and
SKIPs them (environment gap, never a failure) when not yet materialized.

Usage: python3 quick-build/corpus/harness/fetch_vendored.py [--apps name,name]
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

HARNESS_DIR = Path(__file__).resolve().parent
CORPUS_DIR = HARNESS_DIR.parent
CACHE_DIR = CORPUS_DIR / ".cache"


def run(cmd: list, cwd: Path) -> None:
    proc = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"command failed ({' '.join(cmd)}):\n{proc.stderr.strip()}")


def fetch_upstream(repo: str, commit: str) -> Path:
    slug = re.sub(r"[^A-Za-z0-9._-]", "-", repo.rstrip("/").rsplit("/", 1)[-1])
    dest = CACHE_DIR / "upstream" / f"{slug}-{commit[:12]}"
    if (dest / ".git").is_dir():
        return dest  # immutable per sha; already fetched
    dest.mkdir(parents=True, exist_ok=True)
    run(["git", "init", "-q"], cwd=dest)
    run(["git", "remote", "add", "origin", repo], cwd=dest)
    run(["git", "fetch", "-q", "--depth", "1", "origin", commit], cwd=dest)
    return dest


def materialize(app_dir: Path) -> Path:
    vendor = json.loads((app_dir / "vendor.json").read_text())
    upstream = fetch_upstream(vendor["repo"], vendor["commit"])

    out = CACHE_DIR / "apps" / app_dir.name
    if out.exists():
        shutil.rmtree(out)  # cheap; always rebuild from the repo app dir + cache
    shutil.copytree(app_dir, out)

    for src, dst in vendor.get("files", {}).items():
        # git checkout keeps the cache tree matching the pinned commit exactly
        run(["git", "checkout", "-q", "FETCH_HEAD", "--", src], cwd=upstream)
        target = out / dst
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(upstream / src, target)

    # "trees" maps an upstream DIRECTORY to an app-relative one, for an entry that takes a
    # whole module rather than a hand-picked slice - listing a few hundred files by hand
    # would be unreadable and would drift from upstream on any rename.
    for src, dst in vendor.get("trees", {}).items():
        run(["git", "checkout", "-q", "FETCH_HEAD", "--", src], cwd=upstream)
        target = out / dst
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(upstream / src, target)

    # Edit patches: <File>.<ext>.patch beside meta.json -> the replacement file
    # meta.json names. The patch base is the same-named file the vendor mapping
    # just materialized (patches are against the pinned commit).
    by_name = {Path(dst).name: out / dst for dst in vendor.get("files", {}).values()}
    # Tree-materialized files are patchable too, indexed by name like the explicit ones.
    # An ambiguous name would silently patch the wrong file, so record the clash and fail
    # only if a patch actually asks for it.
    ambiguous = set()
    for dst in vendor.get("trees", {}).values():
        for path in sorted((out / dst).rglob("*")):
            if not path.is_file():
                continue
            if path.name in by_name:
                ambiguous.add(path.name)
            by_name[path.name] = path
    for patch in sorted(out.glob("edits/*/*.patch")):
        replacement = patch.with_suffix("")  # Floats.java.patch -> Floats.java
        base = by_name.get(replacement.name)
        if base is None or not base.is_file():
            raise SystemExit(f"{patch}: no vendored base file named {replacement.name}")
        if replacement.name in ambiguous:
            raise SystemExit(
                f"{patch}: {replacement.name} is not a unique name in the vendored trees;"
                " rename the patch target or vendor that file explicitly under 'files'"
            )
        proc = subprocess.run(
            ["patch", "-u", "-s", str(base), "-o", str(replacement)],
            stdin=patch.open("rb"),
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            raise SystemExit(
                f"{patch} does not apply to {base} at commit {vendor['commit'][:12]}:"
                f"\n{proc.stderr.strip()}"
            )
        patch.unlink()
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apps", default=None, help="comma-separated app names (default: all vendored apps)")
    args = parser.parse_args()

    vendored = sorted(
        p for p in (CORPUS_DIR / "apps").iterdir() if p.is_dir() and (p / "vendor.json").is_file()
    )
    if args.apps:
        wanted = set(args.apps.split(","))
        vendored = [p for p in vendored if p.name in wanted]
    if not vendored:
        print("no vendored apps matched; nothing to do")
        return

    for app_dir in vendored:
        out = materialize(app_dir)
        n = sum(1 for f in out.rglob("*") if f.is_file())
        print(f"{app_dir.name}: materialized {n} files -> {out.relative_to(CORPUS_DIR)}")


if __name__ == "__main__":
    main()

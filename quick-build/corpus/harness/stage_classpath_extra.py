#!/usr/bin/env python3
"""Stage the androidx/kotlinx jars the real-app corpus tier needs onto
--classpath-extra, without a Gradle invocation.

The large-real-app and SecUSo/popular-apps tiers (sora-editor-lib,
streetcomplete-lib, findroid, kiss, seal, jcomposecogo, notes, pedometer,
qr-scanner, sudoku, todo-list, ...) vendor real upstream source that imports
real androidx/kotlinx symbols -- Room entities, ContextCompat, ViewModel,
appcompat/fragment/viewpager widgets, kotlinx.coroutines.flow,
kotlinx.serialization. Every prior session re-derived the needed jars by hand
from the local Gradle module cache (see the corpus README's "Large-real-app
tier" section, and WS-A1-status.md's "classpath-extra gap on resume" finding)
-- this script makes that derivation a checked-in, idempotent fixture instead.

Deliberately NOT Gradle-based (unlike resolve_classpath.py, which shells out
to `./gradlew -p resolve-project`): this script only reads files already
resolved into ~/.gradle/caches/modules-2/files-2.1 by a prior real build (the
CoGo build itself, or any Gradle build run against this repo's flox env) --
it never invokes Gradle. That matters when another process in the same
worktree is mid-build: a second concurrent Gradle invocation risks daemon/
lock contention with it. If a coordinate below isn't present in the cache,
run any Gradle build that resolves it once (or use resolve_classpath.py
directly, from a worktree with no concurrent Gradle activity), then re-run
this script.

DECLARED_ARTIFACTS below is the full set: WS-A1's original six jars
(androidx.core, androidx.lifecycle-viewmodel, androidx.room-common-jvm,
androidx.annotation-jvm, kotlinx-serialization-core-jvm,
kotlinx-coroutines-core-jvm) plus everything the current corpus's vendored
real-app imports additionally need -- derived two ways: (1) grepping every
materialized vendored app's real source for `^import (androidx|kotlinx)\\.`
and mapping each package to its Maven artifact (appcompat, collection,
drawerlayout, fragment, legacy-support-core-utils, lifecycle-common/
livedata-core, localbroadcastmanager, room-runtime, sqlite/sqlite-framework,
viewpager), and (2) running a full matrix pass with (1) staged and reading
the remaining javac/kotlinc diagnostics for what's STILL missing --
androidx.customview/activity/savedstate (referenced only transitively, by
appcompat's own class files' supertypes -- no vendored app imports them
directly, but javac still needs them on the classpath to typecheck anything
that touches AppCompatActivity/DrawerLayout), com.google.android.material
(NavigationView/TabLayout/MaterialAlertDialogBuilder), com.google.zxing +
com.journeyapps.zxing-android-embedded + com.google.code.gson (qr-scanner's
barcode generation), org.apache.commons:commons-lang3 (antennapod-model's
StringUtils), and org.jetbrains:annotations (pedometer's plain
@NotNull/@Nullable, distinct from androidx.annotation). Extend
DECLARED_ARTIFACTS (not this script's logic) when a new vendored app needs
another symbol -- rerun the full matrix and read the diagnostics rather than
guessing from imports alone, since (2) above is easy to miss from source
grep alone.

Usage:
    python3 stage_classpath_extra.py
    python3 stage_classpath_extra.py --gradle-cache /some/other/GRADLE_USER_HOME/caches/modules-2/files-2.1
    python3 stage_classpath_extra.py --print-flags-only   # skip re-staging, just print the flags

Prints, on success:
  - one staged jar path per line
  - a single shell-ready `--classpath-extra <jar> --classpath-extra <jar> ...` string

Fails loudly (SystemExit, naming the missing artifact) if any DECLARED_ARTIFACTS
entry isn't present in the scanned cache -- this is a fixture, not a best-effort
scan, so a silent gap here should never turn into a silent SKIP later in
run_matrix.py.
"""

from __future__ import annotations

import argparse
import shutil
import sys
import zipfile
from pathlib import Path
from typing import List, NamedTuple, Optional

HARNESS_DIR = Path(__file__).resolve().parent
CORPUS_DIR = HARNESS_DIR.parent
STAGE_DIR = CORPUS_DIR / ".cache" / "classpath-extra"
DEFAULT_GRADLE_CACHE = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"


class Artifact(NamedTuple):
    group: str
    artifact: str
    version: str
    kind: str  # "jar" (used as-is) or "aar" (classes.jar extracted)


# One entry per Maven coordinate a vendored corpus app's real source imports
# directly. Versions are pinned to what a real CoGo/flox build has already
# resolved into the local Gradle cache (see this file's module docstring for
# how the list was derived) -- not "whatever's newest," so a future dependency
# bump elsewhere in the repo can't silently swap the jar a corpus run compiles
# against.
DECLARED_ARTIFACTS: List[Artifact] = [
    Artifact("androidx.annotation", "annotation-jvm", "1.9.1", "jar"),
    Artifact("androidx.appcompat", "appcompat", "1.7.1", "aar"),
    Artifact("androidx.collection", "collection-jvm", "1.5.0", "jar"),
    Artifact("androidx.core", "core", "1.16.0", "aar"),
    Artifact("androidx.drawerlayout", "drawerlayout", "1.2.0", "aar"),
    Artifact("androidx.fragment", "fragment", "1.8.9", "aar"),
    Artifact("androidx.legacy", "legacy-support-core-utils", "1.0.0", "aar"),
    Artifact("androidx.lifecycle", "lifecycle-common-jvm", "2.9.3", "jar"),
    Artifact("androidx.lifecycle", "lifecycle-livedata-core", "2.9.3", "aar"),
    # Plain "lifecycle-viewmodel" is the KMP common-target AAR (manifest + LICENSE
    # only, no classes.jar) -- the real jvm/Android classes live in the "-android"
    # artifact's AAR.
    Artifact("androidx.lifecycle", "lifecycle-viewmodel-android", "2.9.3", "aar"),
    Artifact("androidx.localbroadcastmanager", "localbroadcastmanager", "1.0.0", "aar"),
    Artifact("androidx.room", "room-common-jvm", "2.8.4", "jar"),
    Artifact("androidx.room", "room-runtime", "2.6.1", "aar"),
    Artifact("androidx.sqlite", "sqlite", "2.4.0", "aar"),
    Artifact("androidx.sqlite", "sqlite-framework", "2.4.0", "aar"),
    Artifact("androidx.viewpager", "viewpager", "1.0.0", "aar"),
    Artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2", "jar"),
    Artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.9.0", "jar"),
    # androidx.appcompat's own class files reference these as supertypes/params
    # (DrawerLayout implements Openable, AppCompatActivity's chain touches
    # ComponentDialog/SavedStateRegistryOwner) -- absent, javac fails typechecking
    # ANY class that merely subclasses AppCompatActivity/uses DrawerLayout, even
    # with no direct import of these types (ruler, 2048).
    Artifact("androidx.customview", "customview", "1.1.0", "aar"),
    Artifact("androidx.activity", "activity", "1.10.1", "aar"),
    Artifact("androidx.savedstate", "savedstate", "1.2.1", "aar"),
    # Material Components (NavigationView, TabLayout, MaterialAlertDialogBuilder --
    # ruler, 2048, todo-list).
    Artifact("com.google.android.material", "material", "1.12.0", "aar"),
    # ZXing barcode generation/decoding + its Android wrapper, and Gson (qr-scanner).
    Artifact("com.google.zxing", "core", "3.5.3", "jar"),
    Artifact("com.journeyapps", "zxing-android-embedded", "4.3.0", "aar"),
    Artifact("com.google.code.gson", "gson", "2.12.1", "jar"),
    # org.apache.commons.lang3.StringUtils (antennapod-model).
    Artifact("org.apache.commons", "commons-lang3", "3.18.0", "jar"),
    # org.jetbrains.annotations.NotNull/Nullable, a plain Kotlin-tooling annotation
    # jar some Java sources reference directly (pedometer) -- distinct from
    # androidx.annotation above.
    Artifact("org.jetbrains", "annotations", "24.1.0", "jar"),
    # Glide image loading (2048's screenshot/logo display).
    Artifact("com.github.bumptech.glide", "glide", "4.16.0", "aar"),
]


def _find_source_file(gradle_cache: Path, art: Artifact) -> Path:
    """Locate the artifact's real (non-sources, non-javadoc) jar/aar under
    <gradle_cache>/<group>/<artifact>/<version>/<sha1>/<artifact>-<version>.<ext>.
    Gradle's module cache nests one more level (a sha1-named dir per file) than
    a plain Maven repo layout, so this globs rather than assuming a fixed depth."""
    version_dir = gradle_cache / art.group / art.artifact / art.version
    if not version_dir.is_dir():
        raise SystemExit(
            f"stage_classpath_extra: missing artifact {art.group}:{art.artifact}:{art.version} "
            f"-- no such dir {version_dir}. Run a Gradle build that resolves it (or "
            f"resolve_classpath.py from a worktree with no concurrent Gradle activity), "
            f"then re-run this script."
        )
    ext = "aar" if art.kind == "aar" else "jar"
    candidates = sorted(
        p
        for p in version_dir.rglob(f"*.{ext}")
        if not p.name.endswith(f"-sources.{ext}") and not p.name.endswith(f"-javadoc.{ext}")
    )
    if not candidates:
        raise SystemExit(
            f"stage_classpath_extra: missing artifact {art.group}:{art.artifact}:{art.version} "
            f"-- {version_dir} exists but has no non-sources/-javadoc .{ext} file. "
            f"Contents: {sorted(p.name for p in version_dir.rglob('*') if p.is_file())}"
        )
    return candidates[0]


def _stage_one(gradle_cache: Path, art: Artifact) -> Path:
    """Copies (jar) or extracts classes.jar (aar) into STAGE_DIR under a stable
    name, skipping work if the staged file already exists (idempotent)."""
    staged_name = f"{art.group}_{art.artifact}-{art.version}.jar"
    staged_path = STAGE_DIR / staged_name
    if staged_path.is_file():
        return staged_path

    src = _find_source_file(gradle_cache, art)
    STAGE_DIR.mkdir(parents=True, exist_ok=True)
    if art.kind == "jar":
        shutil.copy2(src, staged_path)
    else:
        with zipfile.ZipFile(src) as z:
            try:
                data = z.read("classes.jar")
            except KeyError:
                raise SystemExit(
                    f"stage_classpath_extra: {src} has no classes.jar entry "
                    f"(resource-only AAR? unexpected for {art.group}:{art.artifact})"
                )
            staged_path.write_bytes(data)
    return staged_path


def stage_all(gradle_cache: Path) -> List[Path]:
    return [_stage_one(gradle_cache, art) for art in DECLARED_ARTIFACTS]


def already_staged() -> Optional[List[Path]]:
    """Returns the staged paths if every declared artifact is already staged,
    else None (caller falls back to a full stage_all pass)."""
    paths = []
    for art in DECLARED_ARTIFACTS:
        p = STAGE_DIR / f"{art.group}_{art.artifact}-{art.version}.jar"
        if not p.is_file():
            return None
        paths.append(p)
    return paths


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--gradle-cache",
        type=Path,
        default=DEFAULT_GRADLE_CACHE,
        help="modules-2/files-2.1 dir to scan (default: ~/.gradle/caches/modules-2/files-2.1)",
    )
    parser.add_argument(
        "--print-flags-only",
        action="store_true",
        help="skip staging; just print flags for jars already staged (fails if any are missing)",
    )
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    if args.print_flags_only:
        staged = already_staged()
        if staged is None:
            print(
                "stage_classpath_extra: --print-flags-only given but not everything is staged yet; "
                "run without --print-flags-only first",
                file=sys.stderr,
            )
            return 2
    else:
        if not args.gradle_cache.is_dir():
            print(f"stage_classpath_extra: no such dir: {args.gradle_cache}", file=sys.stderr)
            return 2
        staged = stage_all(args.gradle_cache)

    for p in staged:
        print(p)
    flag_string = " ".join(f"--classpath-extra {p}" for p in staged)
    print()
    print(flag_string)
    return 0


if __name__ == "__main__":
    sys.exit(main())

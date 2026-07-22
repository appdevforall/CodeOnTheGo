#!/usr/bin/env python3
"""PR quick-buildability survey.

Answers, programmatically and with zero LLM tokens: "of the last N merged PRs in
each of ~100 active Android apps, how many would CoGo's Quick Build handle on the
fast path (vs. fall back to a full Gradle build)?"

The classification is a FAITHFUL PYTHON MIRROR of the Kotlin ground truth:

    quick-build/src/main/java/org/appdevforall/cotg/quickbuild/domain/ChangeClassifier.kt
    quick-build/src/main/java/org/appdevforall/cotg/quickbuild/domain/BuildRoute.kt

Every rule in `_kind()` / `classify_route()` cites the ChangeClassifier line it
mirrors. The Kotlin classifier is PURELY PATH-BASED (it never reads file content --
see the KDoc: "Classification is by path shape, not file content"), so this mirror
is exact: there is no content-dependent decision to conservatively approximate. The
only uncertainty is GitHub API file-list *truncation*, handled conservatively below.

Pure Python 3 stdlib + the `gh` CLI (already authenticated). Deterministic and
resumable: every API response is cached under quick-build/corpus/.cache/pr-survey/
(gitignored), so a re-run skips repos/PRs already fetched. Once launched the scrape
needs no agent.

Subcommands (via flags, single-file tool):
    --resolve-repos      build/refresh the repo roster -> pr-survey-repos.txt
    (default scrape)     --repos-file FILE : fetch + classify + cache per repo
    --report             read cache, emit markdown + JSON summary
    --selftest           run the classifier unit tests (mirrors ChangeClassifierTest)
"""

import argparse
import json
import os
import subprocess
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS_ROOT = os.path.dirname(HERE)                       # quick-build/corpus
CACHE_DIR = os.path.join(CORPUS_ROOT, ".cache", "pr-survey")
VERIFY_DIR = os.path.join(CACHE_DIR, "_verify")
DEFAULT_REPOS_FILE = os.path.join(HERE, "pr-survey-repos.txt")

# ---------------------------------------------------------------------------
# Classification -- the mirror. See ChangeClassifier.kt cited per rule.
# ---------------------------------------------------------------------------

# ChangeClassifier.kt:103-111 (GRADLE_FILE_NAMES companion set).
GRADLE_FILE_NAMES = frozenset({
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
    "local.properties",
})

# Route constants mirror BuildRoute.kt's sealed interface.
FULL_GRADLE_BUILD = "FullGradleBuild"
RESOURCES_ONLY = "ResourcesOnly"
ASSETS_ONLY = "AssetsOnly"
CODE_ONLY = "CodeOnly"
CODE_AND_RESOURCES = "CodeAndResources"
NO_OP = "NoOp"

# InvalidationReason.kt enum values (only the classifier-reachable three).
REASON_MANIFEST = "MANIFEST_CHANGED"
REASON_GRADLE = "GRADLE_CONFIG_CHANGED"
REASON_UNSUPPORTED = "UNSUPPORTED_FILE_CHANGED"

# FileKind mirror (ChangeClassifier.kt:61).
K_GRADLE = "GRADLE_CONFIG"
K_MANIFEST = "MANIFEST"
K_CODE = "CODE"
K_RESOURCE = "RESOURCE"
K_ASSET = "ASSET"
K_UNSUPPORTED = "UNSUPPORTED"

_FALLBACK_KINDS = (K_GRADLE, K_MANIFEST, K_UNSUPPORTED)
_FALLBACK_REASON = {
    K_GRADLE: REASON_GRADLE,
    K_MANIFEST: REASON_MANIFEST,
    K_UNSUPPORTED: REASON_UNSUPPORTED,
}


def _split(path):
    """(filename, [directory segments]) for a POSIX repo-relative path.

    Mirrors ChangeClassifier.hasSegment (ChangeClassifier.kt:89-100), which walks
    java.io.File.parentFile -- i.e. it inspects only the DIRECTORY segments, never
    the filename itself. So `res` matched as a segment must be an ancestor DIR, not
    a file literally named "res".
    """
    parts = [p for p in path.split("/") if p not in ("", ".")]
    if not parts:
        return path, []
    return parts[-1], parts[:-1]


def _kind(path):
    """Classify one path to a FileKind. Exact mirror of ChangeClassifier.kindOf
    (ChangeClassifier.kt:63-87)."""
    name, dirs = _split(path)

    # kt:66-68 -- gradle build files by name, OR any *.toml under a `gradle` dir
    # (version catalogs like gradle/libs.versions.toml).
    if name in GRADLE_FILE_NAMES or (name.endswith(".toml") and "gradle" in dirs):
        return K_GRADLE
    # kt:69-71 -- gradle-wrapper.properties under a `wrapper` dir.
    if "wrapper" in dirs and name == "gradle-wrapper.properties":
        return K_GRADLE
    # kt:72-74 -- AndroidManifest.xml (any location).
    if name == "AndroidManifest.xml":
        return K_MANIFEST

    # kt:76-82 -- res/ and assets/ only count when also under a `src` dir.
    under_src = "src" in dirs
    if under_src and "res" in dirs:
        return K_RESOURCE
    if under_src and "assets" in dirs:
        return K_ASSET
    # kt:83-85 -- .kt/.java are code.
    if name.endswith(".kt") or name.endswith(".java"):
        return K_CODE
    # kt:86 -- everything else is honest-fallback territory (unsupported packaging).
    return K_UNSUPPORTED


def classify_route(paths):
    """Classify the union of changed file paths into a BuildRoute.

    Exact mirror of ChangeClassifier.classify (ChangeClassifier.kt:23-59) for the
    ChangedFiles.Known case (a PR always has a known file set). Returns a dict:
        route, quick_buildable, reason, kind_counts, triggering_kinds,
        trigger_labels

    Precedence note: the Kotlin classifier RETURNS on the first fallback file it
    meets while iterating a Set (ChangeClassifier.kt:40-45) -- so WHICH
    InvalidationReason it reports is iteration-order-dependent and not stable over
    an unordered set. The *route* (FullGradleBuild vs not) and quick_buildable are
    fully deterministic, which is all the survey needs. For a single stable reason
    label we pick a fixed priority GRADLE > MANIFEST > UNSUPPORTED; every triggering
    kind/label is also recorded separately so the report can aggregate causes
    without depending on that tie-break.
    """
    kinds = [_kind(p) for p in paths]
    kind_counts = Counter(kinds)

    # kt:30-32 -- empty known set is a no-op.
    if not kinds:
        return _result(NO_OP, True, None, kind_counts, [], [])

    triggering_kinds = [k for k in _FALLBACK_KINDS if kind_counts.get(k)]
    if triggering_kinds:
        # kt:40-45 -- any gradle/manifest/unsupported file forces FullGradleBuild.
        reason = _FALLBACK_REASON[triggering_kinds[0]]  # fixed priority tie-break
        labels = _fallback_labels(paths)
        return _result(FULL_GRADLE_BUILD, False, reason, kind_counts,
                       triggering_kinds, labels)

    # kt:52-58 -- no fallback file: pick cheapest correct quick route.
    has_code = bool(kind_counts.get(K_CODE))
    has_res = bool(kind_counts.get(K_RESOURCE))
    has_asset = bool(kind_counts.get(K_ASSET))
    if has_code and has_res:
        route = CODE_AND_RESOURCES
    elif has_code:
        route = CODE_ONLY
    elif has_res:
        route = RESOURCES_ONLY
    elif has_asset:
        route = ASSETS_ONLY
    else:
        route = NO_OP
    return _result(route, True, None, kind_counts, [], [])


def _result(route, quick, reason, kind_counts, triggering_kinds, labels):
    return {
        "route": route,
        "quick_buildable": quick,
        "reason": reason,
        "kind_counts": {
            "code": kind_counts.get(K_CODE, 0),
            "resource": kind_counts.get(K_RESOURCE, 0),
            "asset": kind_counts.get(K_ASSET, 0),
            "gradle": kind_counts.get(K_GRADLE, 0),
            "manifest": kind_counts.get(K_MANIFEST, 0),
            "unsupported": kind_counts.get(K_UNSUPPORTED, 0),
        },
        "triggering_kinds": triggering_kinds,
        "trigger_labels": labels,
    }


def _fallback_labels(paths):
    """Human-readable cause labels for the fallback files in a PR, for aggregation.

    Fine-grained: gradle files by filename, manifest as itself, unsupported files by
    a '<ext> (<hint>)' descriptor so `.so` under jniLibs and a stray `.properties`
    java-resource read as distinct causes in the report.
    """
    labels = set()
    for p in paths:
        k = _kind(p)
        name, dirs = _split(p)
        if k == K_GRADLE:
            if name.endswith(".toml"):
                labels.add("version catalog (%s)" % name)
            elif name == "gradle-wrapper.properties":
                labels.add("gradle-wrapper.properties")
            else:
                labels.add(name)
        elif k == K_MANIFEST:
            labels.add("AndroidManifest.xml")
        elif k == K_UNSUPPORTED:
            ext = ("." + name.rsplit(".", 1)[1]) if "." in name else "(no ext)"
            if "jniLibs" in dirs or ext == ".so":
                labels.add("native lib %s (jniLibs)" % ext)
            elif "src" in dirs:
                labels.add("unsupported %s under src/" % ext)
            else:
                labels.add("non-source %s" % ext)
    return sorted(labels)


# ---------------------------------------------------------------------------
# What-if: a PROPOSED ignore-list for non-buildable files.
#
# ChangeClassifier today has no ignore-list: any file that is not code / a res or
# asset under src/ / a gradle or manifest file is UNSUPPORTED -> full Gradle build
# (ChangeClassifier.kt:86). So a PR that only touches a README, a CI workflow, or a
# .gitignore alongside code falls back even though none of those affect the build
# graph. This models a proposed semantics change where such files DON'T invalidate a
# quick build, so the residual (code/res/asset) drives the route.
#
# THIS IS A PROPOSED-SEMANTICS ESTIMATE, not the real classifier. The ignore-list is
# deliberately conservative (see below) so the counterfactual rate is a LOWER BOUND.
# ---------------------------------------------------------------------------

# Files a quick build can safely NOT rebuild for. Applied only OUTSIDE `src/` --
# anything under a module's src/ is a real build input and is never ignored.
_IGNORABLE_EXTS = frozenset({
    ".md", ".markdown", ".txt", ".rst", ".adoc",      # docs
    ".yml", ".yaml",                                  # CI configs (GitHub Actions etc.)
    ".gitignore", ".gitattributes", ".editorconfig",  # VCS/editor metadata
})
_IGNORABLE_DIRS = frozenset({"docs", ".github", "fastlane"})  # docs trees, CI, store metadata
_IGNORABLE_NAMES = frozenset({
    "LICENSE", "LICENSE.txt", "LICENSE.md", "NOTICE", "COPYING",
    "CODEOWNERS", ".gitignore", ".gitattributes", ".editorconfig",
})

# Human-readable description printed in the report.
IGNORE_LIST_DESC = [
    "Docs: *.md, *.markdown, *.txt, *.rst, *.adoc, and anything under a `docs/` dir",
    "CI/VCS/editor: *.yml, *.yaml, anything under `.github/`, .gitignore, "
    ".gitattributes, .editorconfig",
    "Project metadata: LICENSE/NOTICE/COPYING/CODEOWNERS, anything under `fastlane/`",
    "Scope: only OUTSIDE a module `src/` dir -- files under src/ are always build inputs",
]

# Cache-summary equivalent of the ext-based rule above, matched against the
# `trigger_labels` a fallback PR carries (the report works from cached summaries, not
# raw paths). Conservative: extensionless files (LICENSE/CODEOWNERS show up as
# "non-source (no ext)") and non-yaml files under .github/ are NOT matched here, so
# the summary estimate under-counts flips vs. the exact path rule -> a lower bound.
_IGNORABLE_LABELS = frozenset(
    "non-source %s" % e for e in _IGNORABLE_EXTS
)


def is_ignorable(path):
    """True if the PROPOSED ignore-list would treat `path` as not invalidating a
    quick build. Exact, path-based (used by classify_route_counterfactual + tests)."""
    name, dirs = _split(path)
    if "src" in dirs:               # module build input -- never ignore
        return False
    if _IGNORABLE_DIRS & set(dirs):
        return True
    if name in _IGNORABLE_NAMES:
        return True
    ext = ("." + name.rsplit(".", 1)[1].lower()) if "." in name else ""
    return ext in _IGNORABLE_EXTS


def classify_route_counterfactual(paths):
    """Exact what-if: drop ignorable files, then classify the residual with the real
    classifier mirror. Only fallback PRs can change; a PR with a genuine invalidator
    (gradle/manifest/unsupported-under-src/native lib) still falls back."""
    return classify_route([p for p in paths if not is_ignorable(p)])


def counterfactual_from_summary(pr):
    """Cache-only what-if for one classified PR summary (kind_counts + trigger_labels).

    Returns (flipped, new_route, new_quick_buildable). Conservative lower bound (see
    _IGNORABLE_LABELS). Agrees with classify_route_counterfactual on raw paths for the
    common cases -- asserted in the test suite.
    """
    if pr["quick_buildable"]:
        return (False, pr["route"], True)          # quick PRs have no fallback files
    kc = pr["kind_counts"]
    if kc["gradle"] or kc["manifest"]:
        return (False, pr["route"], False)         # genuine invalidator remains
    # kc.gradle == kc.manifest == 0, so every trigger label is unsupported-category.
    labels = pr.get("trigger_labels", [])
    if any(lbl not in _IGNORABLE_LABELS for lbl in labels):
        return (False, pr["route"], False)         # a non-ignorable unsupported file
    # All fallback drivers were ignorable: route on the residual code/res/asset.
    has_code, has_res, has_asset = kc["code"] > 0, kc["resource"] > 0, kc["asset"] > 0
    if has_code and has_res:
        route = CODE_AND_RESOURCES
    elif has_code:
        route = CODE_ONLY
    elif has_res:
        route = RESOURCES_ONLY
    elif has_asset:
        route = ASSETS_ONLY
    else:
        route = NO_OP                              # e.g. a docs-only PR: nothing to build
    return (True, route, True)


# ---------------------------------------------------------------------------
# gh CLI plumbing
# ---------------------------------------------------------------------------

def _run_gh(args, check=True):
    """Run a gh command, return (rc, stdout, stderr)."""
    proc = subprocess.run(
        ["gh"] + args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        universal_newlines=True,
    )
    if check and proc.returncode != 0:
        raise RuntimeError("gh %s failed: %s" % (" ".join(args), proc.stderr.strip()))
    return proc.returncode, proc.stdout, proc.stderr


def _gh_graphql(query, variables, max_retries=6):
    """Run a GraphQL query via `gh api graphql`, with rate-limit backoff.

    On a secondary/primary rate-limit error, sleep and retry rather than dying, so a
    detached scrape survives a throttle window.
    """
    args = ["api", "graphql", "-f", "query=%s" % query]
    for k, v in variables.items():
        if isinstance(v, bool):
            args += ["-F", "%s=%s" % (k, "true" if v else "false")]
        elif isinstance(v, int):
            args += ["-F", "%s=%d" % (k, v)]
        elif v is None:
            continue
        else:
            args += ["-f", "%s=%s" % (k, v)]

    delay = 30
    for attempt in range(max_retries):
        rc, out, err = _run_gh(args, check=False)
        if rc == 0:
            try:
                data = json.loads(out)
            except ValueError:
                raise RuntimeError("gh graphql returned non-JSON: %s" % out[:200])
            if "errors" in data and data["errors"]:
                msg = json.dumps(data["errors"])
                if "RATE_LIMITED" in msg or "rate limit" in msg.lower():
                    _sleep_for_rate_limit(delay)
                    delay = min(delay * 2, 900)
                    continue
                # NOT_FOUND / access errors: return the payload, caller decides.
                return data
            return data
        low = err.lower()
        if "rate limit" in low or "was submitted too quickly" in low or "abuse" in low:
            _sleep_for_rate_limit(delay)
            delay = min(delay * 2, 900)
            continue
        # transient network hiccup: brief retry
        if attempt < max_retries - 1:
            time.sleep(10)
            continue
        raise RuntimeError("gh graphql failed after retries: %s" % err.strip())
    raise RuntimeError("gh graphql: exhausted retries (rate limited)")


def _sleep_for_rate_limit(fallback_seconds):
    """Sleep until the GraphQL rate limit resets (or a fallback interval)."""
    secs = fallback_seconds
    rc, out, _ = _run_gh(["api", "rate_limit"], check=False)
    if rc == 0:
        try:
            reset = json.loads(out)["resources"]["graphql"]["reset"]
            secs = max(5, int(reset) - int(time.time()) + 5)
        except (ValueError, KeyError):
            pass
    secs = min(secs, 3600)
    _log("rate limited; sleeping %ds" % secs)
    time.sleep(secs)


def _log(msg):
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    line = "[%s] %s" % (ts, msg)
    print(line, flush=True)


# ---------------------------------------------------------------------------
# Scrape
# ---------------------------------------------------------------------------

PR_QUERY = """
query($owner:String!, $name:String!, $count:Int!) {
  rateLimit { remaining resetAt }
  repository(owner:$owner, name:$name) {
    pullRequests(states:MERGED, first:$count,
                 orderBy:{field:UPDATED_AT, direction:DESC}) {
      nodes {
        number
        title
        mergedAt
        files(first:100) {
          totalCount
          pageInfo { hasNextPage endCursor }
          nodes { path additions deletions changeType }
        }
      }
    }
  }
}
"""

FILES_PAGE_QUERY = """
query($owner:String!, $name:String!, $number:Int!, $cursor:String) {
  repository(owner:$owner, name:$name) {
    pullRequest(number:$number) {
      files(first:100, after:$cursor) {
        pageInfo { hasNextPage endCursor }
        nodes { path additions deletions changeType }
      }
    }
  }
}
"""


def _cache_path(owner, name):
    return os.path.join(CACHE_DIR, "%s__%s.json" % (owner, name))


def _all_pr_files(owner, name, number, first_page):
    """Return the complete file list for a PR, paginating past the first 100 if
    truncated. Correctness matters: a hidden gradle/manifest file past file #100
    would flip the route."""
    files = list(first_page["nodes"])
    page = first_page["pageInfo"]
    while page.get("hasNextPage"):
        data = _gh_graphql(FILES_PAGE_QUERY, {
            "owner": owner, "name": name,
            "number": number, "cursor": page["endCursor"],
        })
        f = data["data"]["repository"]["pullRequest"]["files"]
        files += f["nodes"]
        page = f["pageInfo"]
    return files


def scrape_repo(owner, name, count, refresh=False):
    """Fetch + classify the last `count` merged PRs of one repo; cache the result.

    Returns "cached" | "done" | "notfound" | "empty".
    """
    out_path = _cache_path(owner, name)
    if os.path.exists(out_path) and not refresh:
        return "cached"

    # Fetch up to 2x count (capped at 100 by the API) ordered by recent update, then
    # sort by mergedAt desc client-side and take `count` -- the closest stable proxy
    # for "last N merged" (the API has no ORDER BY mergedAt).
    fetch_n = min(100, max(count, count * 2))
    data = _gh_graphql(PR_QUERY, {"owner": owner, "name": name, "count": fetch_n})
    if data.get("errors"):
        _log("  %s/%s: %s" % (owner, name, data["errors"][0].get("type", "error")))
        return "notfound"
    repo = data["data"]["repository"]
    if repo is None:
        return "notfound"

    nodes = repo["pullRequests"]["nodes"]
    nodes = [n for n in nodes if n.get("mergedAt")]
    nodes.sort(key=lambda n: n["mergedAt"], reverse=True)
    nodes = nodes[:count]

    prs = []
    for n in nodes:
        files_block = n["files"]
        if files_block["totalCount"] > len(files_block["nodes"]):
            file_nodes = _all_pr_files(owner, name, n["number"], files_block)
        else:
            file_nodes = files_block["nodes"]
        paths = [f["path"] for f in file_nodes]
        adds = sum(f.get("additions", 0) for f in file_nodes)
        dels = sum(f.get("deletions", 0) for f in file_nodes)
        cls = classify_route(paths)
        prs.append({
            "number": n["number"],
            "title": n["title"],
            "mergedAt": n["mergedAt"],
            "file_count": len(paths),
            "additions": adds,
            "deletions": dels,
            "route": cls["route"],
            "quick_buildable": cls["quick_buildable"],
            "reason": cls["reason"],
            "kind_counts": cls["kind_counts"],
            "trigger_labels": cls["trigger_labels"],
        })

    payload = {
        "repo": "%s/%s" % (owner, name),
        "fetched_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "requested": count,
        "pr_count": len(prs),
        "prs": prs,
    }
    _atomic_write_json(out_path, payload)
    return "done" if prs else "empty"


def cmd_scrape(args):
    os.makedirs(CACHE_DIR, exist_ok=True)
    repos = _read_repos_file(args.repos_file)
    _log("scrape: %d repos, %d merged PRs each -> %s"
         % (len(repos), args.count, CACHE_DIR))
    stats = Counter()
    for i, full in enumerate(repos, 1):
        owner, _, name = full.partition("/")
        if not name:
            _log("  skip malformed repo line: %r" % full)
            continue
        try:
            status = scrape_repo(owner, name, args.count, refresh=args.refresh)
        except RuntimeError as e:
            _log("  %s ERROR: %s" % (full, e))
            stats["error"] += 1
            continue
        stats[status] += 1
        _log("  [%d/%d] %s: %s" % (i, len(repos), full, status))
    _log("scrape complete: %s" % dict(stats))
    _log("run --report to summarize")


# ---------------------------------------------------------------------------
# Repo roster resolution
# ---------------------------------------------------------------------------

# Seed roster: the corpus's real-world reference apps + well-known active FOSS
# Android apps. Verification (below) drops any that no longer look like an Android
# app, so a stale/renamed guess is self-correcting.
SEED_REPOS = [
    "Rosemoe/sora-editor",
    "streetcomplete/StreetComplete",
    "android/architecture-samples",
    "Ashinch/ReadYou",
    "AntennaPod/AntennaPod",
    "JunkFood02/Seal",
    "Neamar/KISS",
    "jarnedemeulemeester/findroid",
    "SecUSo/privacy-friendly-notes",
    "SecUSo/privacy-friendly-todo-list",
    "SecUSo/privacy-friendly-qr-scanner",
    "bitfireAT/davx5-ose",
    "gsantner/markor",
    "TeamNewPipe/NewPipe",
    "signalapp/Signal-Android",
    "wordpress-mobile/WordPress-Android",
    "duckduckgo/Android",
    "mozilla-mobile/firefox-android",
    "vinaygaba/Learn-Jetpack-Compose-By-Example",
    "chrisbanes/tivi",
]


def cmd_resolve_repos(args):
    os.makedirs(VERIFY_DIR, exist_ok=True)
    target = args.target
    candidates = list(SEED_REPOS)
    seen = set(candidates)

    for lang in ("kotlin", "java"):
        for full in _search_android_repos(lang, limit=200):
            if full not in seen:
                seen.add(full)
                candidates.append(full)

    _log("resolve: %d candidates; verifying (target %d)..."
         % (len(candidates), target))
    resolved = []
    for full in candidates:
        if len(resolved) >= target:
            break
        owner, _, name = full.partition("/")
        if not name:
            continue
        ok = _verify_android_app(owner, name, refresh=args.refresh)
        if ok:
            resolved.append(full)
        _log("  %s: %s (%d/%d)"
             % (full, "OK" if ok else "skip", len(resolved), target))

    _write_repos_file(args.repos_file, resolved)
    _log("wrote %d repos -> %s" % (len(resolved), args.repos_file))


def _search_android_repos(language, limit):
    rc, out, err = _run_gh([
        "search", "repos",
        "--topic", "android",
        "--language", language,
        "--sort", "updated",
        "--limit", str(limit),
        "--json", "fullName",
    ], check=False)
    if rc != 0:
        _log("  gh search (%s) failed: %s" % (language, err.strip()))
        return []
    try:
        return [r["fullName"] for r in json.loads(out)]
    except ValueError:
        return []


def _verify_android_app(owner, name, refresh=False):
    """True if the repo root looks like a Gradle Android app: a settings.gradle(.kts)
    AND a gradle wrapper (gradlew). One cached contents call per repo."""
    vpath = os.path.join(VERIFY_DIR, "%s__%s.json" % (owner, name))
    if os.path.exists(vpath) and not refresh:
        try:
            with open(vpath) as fh:
                return json.load(fh)["android"]
        except (ValueError, KeyError):
            pass
    rc, out, err = _run_gh(
        ["api", "repos/%s/%s/contents" % (owner, name)], check=False)
    android = False
    if rc == 0:
        try:
            entries = {e["name"] for e in json.loads(out)}
            has_settings = bool(entries & {"settings.gradle", "settings.gradle.kts"})
            has_wrapper = "gradlew" in entries
            android = has_settings and has_wrapper
        except ValueError:
            android = False
    elif "rate limit" in err.lower():
        _sleep_for_rate_limit(30)
        return _verify_android_app(owner, name, refresh=True)
    _atomic_write_json(vpath, {"repo": "%s/%s" % (owner, name), "android": android})
    return android


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

def cmd_report(args):
    caches = []
    if os.path.isdir(CACHE_DIR):
        for fn in sorted(os.listdir(CACHE_DIR)):
            if fn.endswith(".json") and not fn.startswith("_"):
                with open(os.path.join(CACHE_DIR, fn)) as fh:
                    caches.append(json.load(fh))
    if not caches:
        _log("no cache found in %s -- run the scrape first" % CACHE_DIR)
        sys.exit(1)

    all_prs = []
    per_repo = []
    for c in caches:
        prs = c["prs"]
        all_prs.extend(prs)
        qb = sum(1 for p in prs if p["quick_buildable"])
        per_repo.append({
            "repo": c["repo"],
            "pr_count": len(prs),
            "quick_buildable": qb,
            "pct": (100.0 * qb / len(prs)) if prs else 0.0,
        })

    total = len(all_prs)
    qb_total = sum(1 for p in all_prs if p["quick_buildable"])
    route_dist = Counter(p["route"] for p in all_prs)

    # Top fallback causes: count PRs (not files) exhibiting each trigger label.
    cause_counter = Counter()
    for p in all_prs:
        for lbl in set(p.get("trigger_labels", [])):
            cause_counter[lbl] += 1

    size_cut = args.size_cut
    small = [p for p in all_prs if p["file_count"] <= size_cut]
    large = [p for p in all_prs if p["file_count"] > size_cut]

    def pct(subset):
        if not subset:
            return 0.0
        return 100.0 * sum(1 for p in subset if p["quick_buildable"]) / len(subset)

    pct_total = (100.0 * qb_total / total) if total else 0.0
    headline = (
        "Quick Build would handle %d of %d recent merged PRs (%.1f%%) across %d "
        "active Android apps on its fast path; the rest need a full Gradle build."
        % (qb_total, total, pct_total, len(caches))
    )

    # What-if: apply the proposed ignore-list for non-buildable files.
    cf_flipped = 0
    cf_residual_routes = Counter()
    for p in all_prs:
        flipped, new_route, _ = counterfactual_from_summary(p)
        if flipped:
            cf_flipped += 1
            cf_residual_routes[new_route] += 1
    cf_qb_total = qb_total + cf_flipped
    cf_pct = (100.0 * cf_qb_total / total) if total else 0.0
    cf_headline = (
        "%.1f%% today -> %.1f%% with the proposed ignore-list "
        "(+%d PRs flip from full Gradle build to a quick route; "
        "PROPOSED-SEMANTICS ESTIMATE, conservative lower bound)."
        % (pct_total, cf_pct, cf_flipped)
    )

    summary = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "repos": len(caches),
        "total_prs": total,
        "quick_buildable": qb_total,
        "quick_buildable_pct": round(pct_total, 1),
        "route_distribution": dict(route_dist),
        "top_fallback_causes": cause_counter.most_common(10),
        "size_cut_files": size_cut,
        "quick_buildable_pct_le_cut": round(pct(small), 1),
        "quick_buildable_pct_gt_cut": round(pct(large), 1),
        "prs_le_cut": len(small),
        "prs_gt_cut": len(large),
        "headline": headline,
        "counterfactual": {
            "ignore_list": IGNORE_LIST_DESC,
            "headline": cf_headline,
            "quick_buildable": cf_qb_total,
            "quick_buildable_pct": round(cf_pct, 1),
            "flipped_prs": cf_flipped,
            "residual_route_distribution": dict(cf_residual_routes),
            "note": "Proposed-semantics estimate from cached PR summaries. The real "
                    "ChangeClassifier has NO ignore-list today. Conservative lower "
                    "bound: extensionless files (LICENSE) and non-yaml .github/ files "
                    "are not counted as ignorable in the summary estimate.",
        },
        "per_repo": sorted(per_repo, key=lambda r: r["pct"], reverse=True),
    }

    md = _render_markdown(summary)

    if args.out_dir:
        os.makedirs(args.out_dir, exist_ok=True)
        with open(os.path.join(args.out_dir, "pr-quickbuild-survey.json"), "w") as fh:
            json.dump(summary, fh, indent=2)
        with open(os.path.join(args.out_dir, "pr-quickbuild-survey.md"), "w") as fh:
            fh.write(md)
        _log("wrote report to %s" % args.out_dir)
    else:
        print(md)
    print("\nHEADLINE: " + headline)
    print("WHAT-IF: " + cf_headline)


def _render_markdown(s):
    lines = []
    lines.append("# PR Quick-Buildability Survey")
    lines.append("")
    lines.append("_Generated %s. Classification mirrors `ChangeClassifier.kt` "
                 "(path-based, exact)._" % s["generated_at"])
    lines.append("")
    lines.append("**%s**" % s["headline"])
    lines.append("")
    lines.append("## Overall")
    lines.append("")
    lines.append("| Metric | Value |")
    lines.append("|---|---|")
    lines.append("| Repos surveyed | %d |" % s["repos"])
    lines.append("| Merged PRs classified | %d |" % s["total_prs"])
    lines.append("| Quick-buildable | %d (%.1f%%) |"
                 % (s["quick_buildable"], s["quick_buildable_pct"]))
    lines.append("| Quick-buildable, PRs <= %d files | %.1f%% (%d PRs) |"
                 % (s["size_cut_files"], s["quick_buildable_pct_le_cut"],
                    s["prs_le_cut"]))
    lines.append("| Quick-buildable, PRs > %d files | %.1f%% (%d PRs) |"
                 % (s["size_cut_files"], s["quick_buildable_pct_gt_cut"],
                    s["prs_gt_cut"]))
    lines.append("")
    cf = s["counterfactual"]
    lines.append("## What-if: a proposed ignore-list for non-buildable files")
    lines.append("")
    lines.append("> **%s**" % cf["headline"])
    lines.append("")
    lines.append("_%s_" % cf["note"])
    lines.append("")
    lines.append("Proposed ignore-list (files that would NOT invalidate a quick build):")
    lines.append("")
    for item in cf["ignore_list"]:
        lines.append("- %s" % item)
    lines.append("")
    lines.append("| Metric | Value |")
    lines.append("|---|---|")
    lines.append("| Quick-buildable today | %d (%.1f%%) |"
                 % (s["quick_buildable"], s["quick_buildable_pct"]))
    lines.append("| Quick-buildable with ignore-list | %d (%.1f%%) |"
                 % (cf["quick_buildable"], cf["quick_buildable_pct"]))
    lines.append("| PRs that flip (full Gradle -> quick) | %d |" % cf["flipped_prs"])
    lines.append("")
    lines.append("Residual routes of the flipped PRs (what they'd build instead):")
    lines.append("")
    lines.append("| Residual route | PRs |")
    lines.append("|---|---|")
    for route, n in sorted(cf["residual_route_distribution"].items(),
                           key=lambda kv: kv[1], reverse=True):
        lines.append("| %s | %d |" % (route, n))
    lines.append("")
    lines.append("## Route distribution")
    lines.append("")
    lines.append("| Route | PRs | Quick? |")
    lines.append("|---|---|---|")
    for route, n in sorted(s["route_distribution"].items(),
                           key=lambda kv: kv[1], reverse=True):
        quick = "no" if route == FULL_GRADLE_BUILD else "yes"
        lines.append("| %s | %d | %s |" % (route, n, quick))
    lines.append("")
    lines.append("## Top fallback causes (PRs forced to full Gradle build)")
    lines.append("")
    lines.append("| Cause (triggering file/rule) | PRs |")
    lines.append("|---|---|")
    for lbl, n in s["top_fallback_causes"]:
        lines.append("| %s | %d |" % (lbl, n))
    lines.append("")
    lines.append("## Per-repo")
    lines.append("")
    lines.append("| Repo | PRs | Quick-buildable | % |")
    lines.append("|---|---|---|---|")
    for r in s["per_repo"]:
        lines.append("| %s | %d | %d | %.0f%% |"
                     % (r["repo"], r["pr_count"], r["quick_buildable"], r["pct"]))
    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# File helpers
# ---------------------------------------------------------------------------

def _atomic_write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(obj, fh, indent=2)
    os.replace(tmp, path)


def _read_repos_file(path):
    repos = []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                repos.append(line)
    return repos


def _write_repos_file(path, repos):
    header = [
        "# Android-app repo roster for the PR quick-buildability survey.",
        "# One owner/repo per line. Regenerate with: pr_quickbuild_survey.py --resolve-repos",
        "# Verified at write time: each has a settings.gradle(.kts) + gradlew at root.",
        "",
    ]
    with open(path, "w") as fh:
        fh.write("\n".join(header))
        fh.write("\n".join(repos))
        fh.write("\n")


# ---------------------------------------------------------------------------
# Self-test -- mirrors ChangeClassifierTest.kt case-for-case.
# ---------------------------------------------------------------------------

def run_selftest():
    from test_pr_quickbuild_survey import run_all
    run_all()


# ---------------------------------------------------------------------------

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--repos-file", default=DEFAULT_REPOS_FILE,
                   help="repo roster file (one owner/repo per line)")
    p.add_argument("--count", type=int, default=50,
                   help="merged PRs to classify per repo (default 50)")
    p.add_argument("--refresh", action="store_true",
                   help="re-fetch even if a repo/verify cache exists")
    p.add_argument("--resolve-repos", action="store_true",
                   help="build/refresh the repo roster instead of scraping")
    p.add_argument("--target", type=int, default=100,
                   help="target repo count for --resolve-repos (default 100)")
    p.add_argument("--report", action="store_true",
                   help="summarize the cache to markdown + JSON")
    p.add_argument("--out-dir", default=None,
                   help="--report: write pr-quickbuild-survey.{md,json} here")
    p.add_argument("--size-cut", type=int, default=20,
                   help="--report: file-count threshold for the size split")
    p.add_argument("--selftest", action="store_true",
                   help="run the classifier unit tests and exit")
    args = p.parse_args(argv)

    if args.selftest:
        run_selftest()
        return
    if args.resolve_repos:
        cmd_resolve_repos(args)
        return
    if args.report:
        cmd_report(args)
        return
    cmd_scrape(args)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Unit tests for the PR quick-buildability survey's classification mirror.

These are ported CASE-FOR-CASE from the Kotlin ground truth's test suite:

    quick-build/src/test/java/org/appdevforall/cotg/quickbuild/domain/ChangeClassifierTest.kt

so the Python mirror provably matches the real classifier's semantics. Each test
names the Kotlin test method it mirrors. Pure functions, no network.

Run either way (pytest is not required):
    python3 test_pr_quickbuild_survey.py
    python3 -m pytest test_pr_quickbuild_survey.py
"""

from pr_quickbuild_survey import (
    classify_route,
    classify_route_counterfactual,
    counterfactual_from_summary,
    aggregate_by_commit,
    _stratified_sample,
    is_ignorable,
    CODE_ONLY,
    RESOURCES_ONLY,
    ASSETS_ONLY,
    CODE_AND_RESOURCES,
    FULL_GRADLE_BUILD,
    NO_OP,
    REASON_MANIFEST,
    REASON_GRADLE,
    REASON_UNSUPPORTED,
)


def _route(*paths):
    return classify_route(list(paths))


def _summary(*paths):
    """Build the cached-PR-summary shape counterfactual_from_summary consumes."""
    r = classify_route(list(paths))
    return {
        "quick_buildable": r["quick_buildable"],
        "route": r["route"],
        "kind_counts": r["kind_counts"],
        "trigger_labels": r["trigger_labels"],
    }


# --- ports of ChangeClassifierTest.kt ---

def test_kotlin_source_is_code_only():
    # `kotlin source is code only`
    r = _route("app/src/main/java/com/example/Main.kt")
    assert r["route"] == CODE_ONLY and r["quick_buildable"] is True


def test_java_source_is_code_only():
    # `java source is code only`
    r = _route("app/src/main/java/com/example/Main.java")
    assert r["route"] == CODE_ONLY and r["quick_buildable"] is True


def test_resource_value_file_is_resources_only():
    # `resource value file is resources only`
    r = _route("app/src/main/res/values/strings.xml")
    assert r["route"] == RESOURCES_ONLY and r["quick_buildable"] is True


def test_layout_and_drawable_are_resources_only():
    # `layout and drawable files are resources only`
    r = _route(
        "app/src/main/res/layout/activity_main.xml",
        "app/src/main/res/drawable/icon.png",
    )
    assert r["route"] == RESOURCES_ONLY


def test_asset_file_is_assets_only():
    # `asset file is assets only`
    r = _route("app/src/main/assets/data/levels.json")
    assert r["route"] == ASSETS_ONLY and r["quick_buildable"] is True


def test_mixed_kotlin_and_resource():
    # `mixed kotlin and resource save compiles AND relinks`
    r = _route(
        "app/src/main/java/com/example/Main.kt",
        "app/src/main/res/values/strings.xml",
    )
    assert r["route"] == CODE_AND_RESOURCES


def test_code_with_assets_is_code_only():
    # `code with assets classifies as code only`
    r = _route(
        "app/src/main/java/com/example/Main.kt",
        "app/src/main/assets/data/levels.json",
    )
    assert r["route"] == CODE_ONLY


def test_manifest_change_invalidates():
    # `manifest change invalidates the session`
    r = _route("app/src/main/AndroidManifest.xml")
    assert r["route"] == FULL_GRADLE_BUILD
    assert r["reason"] == REASON_MANIFEST
    assert r["quick_buildable"] is False


def test_gradle_build_files_invalidate():
    # `gradle build file invalidates the session`
    for path in ("app/build.gradle.kts", "settings.gradle", "gradle.properties"):
        r = _route(path)
        assert r["route"] == FULL_GRADLE_BUILD, path
        assert r["reason"] == REASON_GRADLE, path


def test_version_catalog_and_wrapper_invalidate():
    # `version catalog and wrapper properties invalidate the session`
    for path in (
        "gradle/libs.versions.toml",
        "gradle/wrapper/gradle-wrapper.properties",
    ):
        r = _route(path)
        assert r["route"] == FULL_GRADLE_BUILD, path
        assert r["reason"] == REASON_GRADLE, path


def test_invalidation_wins_over_code():
    # `invalidation wins over any accompanying code change`
    r = _route(
        "app/src/main/java/com/example/Main.kt",
        "app/src/main/AndroidManifest.xml",
    )
    assert r["route"] == FULL_GRADLE_BUILD and r["quick_buildable"] is False


def test_unsupported_under_src_falls_back():
    # `unsupported file under src falls back honestly`
    r = _route("app/src/main/resources/config.properties")
    assert r["route"] == FULL_GRADLE_BUILD
    assert r["reason"] == REASON_UNSUPPORTED


def test_native_lib_falls_back():
    # `native library under jniLibs falls back honestly`
    r = _route("app/src/main/jniLibs/arm64-v8a/libnativestub.so")
    assert r["route"] == FULL_GRADLE_BUILD
    assert r["reason"] == REASON_UNSUPPORTED


def test_empty_set_is_noop():
    # `empty known set is a no-op`
    r = _route()
    assert r["route"] == NO_OP and r["quick_buildable"] is True


# --- extra coverage of survey-specific behavior not in the Kotlin suite ---

def test_settings_gradle_kts_and_local_properties():
    # Complete the GRADLE_FILE_NAMES set beyond the Kotlin cases.
    for path in ("settings.gradle.kts", "local.properties", "build.gradle"):
        assert _route(path)["route"] == FULL_GRADLE_BUILD, path


def test_toml_outside_gradle_dir_is_unsupported_not_gradle():
    # ChangeClassifier.kt:66 -- the *.toml rule requires a `gradle` ancestor dir.
    r = _route("app/config/feature.toml")
    assert r["route"] == FULL_GRADLE_BUILD
    assert r["reason"] == REASON_UNSUPPORTED  # UNSUPPORTED, not GRADLE_CONFIG


def test_res_outside_src_is_not_a_resource():
    # ChangeClassifier.kt:77 -- res only counts when also under `src`. A doc image
    # under a top-level res/ dir is not a RESOURCE; a .png is unsupported -> fallback.
    r = _route("res/screenshots/demo.png")
    assert r["route"] == FULL_GRADLE_BUILD
    assert r["reason"] == REASON_UNSUPPORTED


def test_readme_only_pr_falls_back():
    # A docs-only PR (README.md) has no code/res/asset -> UNSUPPORTED -> fallback.
    r = _route("README.md", "docs/CHANGELOG.md")
    assert r["route"] == FULL_GRADLE_BUILD
    assert r["quick_buildable"] is False


def test_fallback_labels_aggregate_distinct_causes():
    r = _route(
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/jniLibs/arm64-v8a/lib.so",
    )
    labels = set(r["trigger_labels"])
    assert "build.gradle.kts" in labels
    assert "AndroidManifest.xml" in labels
    assert any("native lib" in x for x in labels)


def test_kind_counts_populated():
    r = _route(
        "app/src/main/java/A.kt",
        "app/src/main/java/B.java",
        "app/src/main/res/values/strings.xml",
    )
    assert r["kind_counts"]["code"] == 2
    assert r["kind_counts"]["resource"] == 1


# --- what-if ignore-list: exact path-based classify_route_counterfactual ---

def test_ignorable_predicate():
    assert is_ignorable("README.md")
    assert is_ignorable("docs/guide.png")            # under docs/ dir
    assert is_ignorable(".github/workflows/ci.yml")
    assert is_ignorable("LICENSE")
    assert is_ignorable(".gitignore")
    assert is_ignorable("fastlane/metadata/en-US/full_description.txt")
    assert not is_ignorable("app/src/main/java/A.kt")
    assert not is_ignorable("app/src/main/assets/config.yml")  # under src/ -> build input
    assert not is_ignorable("build.gradle.kts")
    assert not is_ignorable("config.json")           # .json not on the list


def test_cf_readme_plus_code_flips_to_code_only():
    # Today: README.md is UNSUPPORTED -> fallback. With ignore-list -> CodeOnly.
    paths = ["README.md", "app/src/main/java/com/example/Main.kt"]
    assert _route(*paths)["route"] == FULL_GRADLE_BUILD
    assert classify_route_counterfactual(paths)["route"] == CODE_ONLY


def test_cf_ci_yaml_plus_code_flips():
    paths = [".github/workflows/build.yml", "app/src/main/java/A.kt"]
    assert classify_route_counterfactual(paths)["route"] == CODE_ONLY


def test_cf_docs_only_pr_becomes_noop():
    paths = ["README.md", "docs/CHANGELOG.md"]
    assert _route(*paths)["route"] == FULL_GRADLE_BUILD
    assert classify_route_counterfactual(paths)["route"] == NO_OP


def test_cf_gradle_change_still_falls_back():
    paths = ["app/build.gradle.kts", "README.md"]
    assert classify_route_counterfactual(paths)["route"] == FULL_GRADLE_BUILD


def test_cf_native_lib_still_falls_back():
    paths = ["app/src/main/jniLibs/arm64-v8a/lib.so", "README.md"]
    assert classify_route_counterfactual(paths)["route"] == FULL_GRADLE_BUILD


def test_cf_nonlisted_json_still_falls_back():
    # .json is deliberately NOT on the ignore-list (could be a real config/asset).
    paths = ["config.json", "app/src/main/java/A.kt"]
    assert classify_route_counterfactual(paths)["route"] == FULL_GRADLE_BUILD


# --- what-if: cache-summary estimator agrees with the exact path version ---

def _assert_summary_matches_exact(*paths):
    flipped, new_route, new_quick = counterfactual_from_summary(_summary(*paths))
    exact = classify_route_counterfactual(list(paths))
    today_quick = classify_route(list(paths))["quick_buildable"]
    assert new_route == exact["route"], (paths, new_route, exact["route"])
    assert new_quick == exact["quick_buildable"]
    assert flipped == (today_quick is False and exact["quick_buildable"] is True)


def test_cf_summary_matches_exact_on_common_cases():
    _assert_summary_matches_exact("README.md", "app/src/main/java/A.kt")
    _assert_summary_matches_exact(".github/workflows/ci.yml", "app/src/main/java/A.kt")
    _assert_summary_matches_exact("README.md", "docs/CHANGELOG.md")
    _assert_summary_matches_exact("app/build.gradle.kts", "README.md")
    _assert_summary_matches_exact("app/src/main/jniLibs/arm64-v8a/l.so", "README.md")
    _assert_summary_matches_exact("app/src/main/java/A.kt")          # already quick
    _assert_summary_matches_exact("app/src/main/res/values/s.xml", "README.txt")
    _assert_summary_matches_exact("CHANGELOG.md",
                                  "app/src/main/java/A.kt",
                                  "app/src/main/res/values/s.xml")   # -> CodeAndResources


def test_cf_summary_flip_flags():
    # A README+code PR flips; a gradle PR does not.
    flipped, route, quick = counterfactual_from_summary(
        _summary("README.md", "app/src/main/java/A.kt"))
    assert flipped is True and route == CODE_ONLY and quick is True
    flipped2, _, quick2 = counterfactual_from_summary(
        _summary("settings.gradle", "README.md"))
    assert flipped2 is False and quick2 is False


# --- per-commit: stratified sampling + by-commit aggregation ---

def test_stratified_sample_includes_extremes():
    repos = [("r%d" % i, float(i)) for i in range(20)]   # pct 0..19
    picks = _stratified_sample(repos, 5)
    assert len(picks) == 5
    assert "r0" in picks and "r19" in picks               # lowest + highest included


def test_stratified_sample_smaller_than_n_returns_all():
    repos = [("a", 1.0), ("b", 2.0)]
    assert set(_stratified_sample(repos, 5)) == {"a", "b"}


def _commit(*paths):
    """A per-commit cache entry (as scrape_repo_commits would write)."""
    r = classify_route(list(paths))
    return {
        "oid": "deadbeef",
        "file_count": len(paths),
        "route": r["route"],
        "quick_buildable": r["quick_buildable"],
        "reason": r["reason"],
        "kind_counts": r["kind_counts"],
        "trigger_labels": r["trigger_labels"],
    }


def test_by_commit_aggregation_cross_cut():
    # One PR whose UNION falls back (a gradle bump commit) but whose other two
    # commits are pure code -> shows PR-level union is a floor.
    caches = [{
        "repo": "o/n",
        "prs": [
            {
                "number": 1,
                "pr_route": FULL_GRADLE_BUILD,
                "pr_quick_buildable": False,
                "commit_count": 3,
                "commits": [
                    _commit("app/build.gradle.kts"),                    # fallback
                    _commit("app/src/main/java/A.kt"),                  # quick
                    _commit("app/src/main/java/B.kt"),                  # quick
                ],
            },
            {
                "number": 2,
                "pr_route": CODE_ONLY,
                "pr_quick_buildable": True,
                "commit_count": 1,
                "commits": [_commit("app/src/main/java/C.kt")],         # quick
            },
        ],
    }]
    agg = aggregate_by_commit(caches)
    assert agg["total_commits"] == 4
    # 3 of 4 commits quick-buildable (the gradle one is the only fallback).
    assert agg["quick_buildable_pct"] == 75.0
    assert agg["fallback_pr_count"] == 1
    assert agg["fallback_pr_commits"] == 3
    # Of the fallback PR's 3 commits, 2 (the code ones) would still quick-build.
    assert abs(agg["fallback_pr_commits_quick_buildable_pct"] - 66.7) < 0.1
    assert agg["commits_per_pr_distribution"] == {1: 1, 3: 1}


def test_by_commit_ignore_list_lifts_pct():
    # A commit that only touches a README falls back today but flips with the
    # ignore-list -> the ignore-list % must be >= the raw %.
    caches = [{
        "repo": "o/n",
        "prs": [{
            "number": 1, "pr_route": FULL_GRADLE_BUILD, "pr_quick_buildable": False,
            "commit_count": 2,
            "commits": [_commit("README.md"), _commit("app/build.gradle.kts")],
        }],
    }]
    agg = aggregate_by_commit(caches)
    assert agg["quick_buildable_pct"] == 0.0                       # both fall back today
    assert agg["quick_buildable_pct_ignore_list"] == 50.0          # README commit flips


def test_by_commit_empty_is_none():
    assert aggregate_by_commit([]) is None


def run_all():
    tests = [v for k, v in sorted(globals().items())
             if k.startswith("test_") and callable(v)]
    failures = 0
    for t in tests:
        try:
            t()
            print("PASS %s" % t.__name__)
        except AssertionError as e:
            failures += 1
            print("FAIL %s: %s" % (t.__name__, e))
    print("\n%d passed, %d failed (of %d)"
          % (len(tests) - failures, failures, len(tests)))
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    run_all()

#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/update_llama_cpp.sh [--ref REF] [--repo URL] [--run-tests] [--test-cmd CMD]

Options:
  --ref REF        Upstream ref (tag/branch/sha). Default: master
  --repo URL       Upstream repo URL. Default: https://github.com/ggml-org/llama.cpp
  --run-tests      Run tests after update (uses --test-cmd if provided)
  --test-cmd CMD   Command to run for tests (default: ./gradlew :llama-impl:assemble)

Examples:
  scripts/update_llama_cpp.sh --ref master
  scripts/update_llama_cpp.sh --ref v0.0.0 --run-tests
  scripts/update_llama_cpp.sh --ref 3a1b2c3 --test-cmd "./gradlew :llama-impl:assemble"
USAGE
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
upstream_repo="https://github.com/ggml-org/llama.cpp"
ref="master"
run_tests="false"
test_cmd="./gradlew :llama-impl:assemble"

while [ $# -gt 0 ]; do
  case "$1" in
    --ref)
      ref="$2"
      shift 2
      ;;
    --repo)
      upstream_repo="$2"
      shift 2
      ;;
    --run-tests)
      run_tests="true"
      shift 1
      ;;
    --test-cmd)
      test_cmd="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

version_file="$repo_root/docs/third_party/llama_cpp.version"
vendored_dir="$repo_root/subprojects/llama.cpp"

if [ ! -d "$vendored_dir" ]; then
  echo "Missing vendored directory: $vendored_dir" >&2
  exit 1
fi

printf 'Repo root: %s\n' "$repo_root"

if git -C "$repo_root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  printf 'Git head: %s\n' "$(git -C "$repo_root" rev-parse --short HEAD)"
  printf 'Git status (short):\n'
  git -C "$repo_root" status --short
else
  printf 'Git: not a git repository.\n'
fi

if [ -f "$version_file" ]; then
  printf '\nCurrent vendored version:\n'
  cat "$version_file"
fi

printf '\nFetching upstream: %s (%s)\n' "$upstream_repo" "$ref"

workdir="$(mktemp -d)"
cleanup() {
  rm -rf "$workdir"
}
trap cleanup EXIT

git -C "$workdir" init -q

git -C "$workdir" remote add origin "$upstream_repo"
# Fetch the ref. For sha refs, this still works but may need full history.
if ! git -C "$workdir" fetch --depth 1 origin "$ref"; then
  git -C "$workdir" fetch origin "$ref"
fi

git -C "$workdir" checkout -q FETCH_HEAD

upstream_commit="$(git -C "$workdir" rev-parse HEAD)"
upstream_date="$(git -C "$workdir" show -s --format=%cs HEAD)"

printf 'Upstream commit: %s (%s)\n' "$upstream_commit" "$upstream_date"

rsync -a --delete \
  --exclude='.git' \
  --exclude='build' \
  --exclude='**/CMakeCache.txt' \
  --exclude='**/CMakeFiles' \
  --exclude='**/*.o' \
  --exclude='**/*.a' \
  --exclude='**/*.so' \
  --exclude='**/*.dylib' \
  --exclude='**/*.dll' \
  "$workdir/" "$vendored_dir/"

mkdir -p "$(dirname "$version_file")"
cat <<EOF_VERSION > "$version_file"
repo: $upstream_repo
ref: $ref
commit: $upstream_commit
date: $upstream_date
updated_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF_VERSION

printf '\nUpdate complete. Diff summary:\n'
if git -C "$repo_root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git -C "$repo_root" diff --stat -- subprojects/llama.cpp docs/third_party/llama_cpp.version || true
else
  printf 'Git diff unavailable.\n'
fi

if [ "$run_tests" = "true" ]; then
  printf '\nRunning tests: %s\n' "$test_cmd"
  (cd "$repo_root" && bash -lc "$test_cmd")
fi

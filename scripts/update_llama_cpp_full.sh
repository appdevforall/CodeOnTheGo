#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/update_llama_cpp_full.sh [--ref REF] [--repo URL] [--test-cmd CMD]

Runs:
  1) status
  2) update
  3) tests

Options:
  --ref REF        Upstream ref (tag/branch/sha). Default: master
  --repo URL       Upstream repo URL. Default: https://github.com/ggml-org/llama.cpp
  --test-cmd CMD   Command to run for tests (default: ./gradlew :llama-impl:assemble)
USAGE
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ref="master"
repo_url="https://github.com/ggml-org/llama.cpp"
test_cmd="./gradlew :llama-impl:assemble"

while [ $# -gt 0 ]; do
  case "$1" in
    --ref)
      ref="$2"
      shift 2
      ;;
    --repo)
      repo_url="$2"
      shift 2
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

printf '== llama.cpp vendor sync: status ==\n'
"$repo_root/scripts/llama_cpp_status.sh"

printf '\n== llama.cpp vendor sync: update ==\n'
"$repo_root/scripts/update_llama_cpp.sh" --ref "$ref" --repo "$repo_url"

printf '\n== llama.cpp vendor sync: tests ==\n'
(cd "$repo_root" && bash -lc "$test_cmd")

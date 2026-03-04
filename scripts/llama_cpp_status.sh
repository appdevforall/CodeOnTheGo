#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version_file="$repo_root/docs/third_party/llama_cpp.version"

printf 'Repo root: %s\n' "$repo_root"

if git -C "$repo_root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  printf 'Git head: %s\n' "$(git -C "$repo_root" rev-parse --short HEAD)"
  printf 'Git status (short):\n'
  git -C "$repo_root" status --short
else
  printf 'Git: not a git repository.\n'
fi

if [ -f "$version_file" ]; then
  printf '\nVendored llama.cpp version:\n'
  cat "$version_file"
else
  printf '\nVendored llama.cpp version: missing (%s)\n' "$version_file"
fi

printf '\nVendored llama.cpp diff summary (if any):\n'
if git -C "$repo_root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git -C "$repo_root" diff --stat -- subprojects/llama.cpp || true
else
  printf 'Git diff unavailable.\n'
fi

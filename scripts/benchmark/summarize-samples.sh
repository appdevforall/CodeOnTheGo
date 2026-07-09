#!/usr/bin/env bash
# Reads a benchmark samples TSV and prints derived metrics.
# Line format: epoch <TAB> mem_available_kb [<TAB> label:pid=heap_used_kb ...]
set -euo pipefail

SAMPLE_FILE="${1:?usage: summarize-samples.sh <samples.tsv>}"

min_avail_kb="$(awk -F'\t' 'NR==1 || $2<min {min=$2} END {print (min=="" ? 0 : min)}' "$SAMPLE_FILE")"
echo "MIN_AVAIL_MB=$(( min_avail_kb / 1024 ))"

peak="$(awk -F'\t' '
  {
    for (i = 3; i <= NF; i++) {
      split($i, kv, "=")
      if (kv[2] + 0 > p[kv[1]]) p[kv[1]] = kv[2]
    }
  }
  END {
    out = ""
    for (k in p) out = out sprintf("%s=%dMB ", k, p[k] / 1024)
    sub(/ $/, "", out)
    print out
  }' "$SAMPLE_FILE")"
echo "PEAK_HEAP=${peak:-n/a}"

#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="$HERE/../summarize-samples.sh"

fixture="$(mktemp)"
printf '1700000000\t9000000\tGradleDaemon:111=2000000\n' >  "$fixture"
printf '1700000002\t7000000\tGradleDaemon:111=3500000\tKotlinCompileDaemon:222=1500000\n' >> "$fixture"

out="$(bash "$SUT" "$fixture")"
echo "$out"

# 7000000 KB / 1024 = 6835 MB  (lowest MemAvailable wins)
grep -qx "MIN_AVAIL_MB=6835" <<<"$out"            || { echo "FAIL: min avail"; exit 1; }
# peak heap per label, KB/1024 rounded down; order not asserted
grep -q  "GradleDaemon:111=3417MB" <<<"$out"       || { echo "FAIL: gradle peak"; exit 1; }
grep -q  "KotlinCompileDaemon:222=1464MB" <<<"$out" || { echo "FAIL: kotlin peak"; exit 1; }
echo "PASS"

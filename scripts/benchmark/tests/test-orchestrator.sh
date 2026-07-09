#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="$HERE/../run-build-benchmark.sh"

workdir="$(mktemp -d)"
stub="$workdir/gradlew-stub.sh"
cat > "$stub" <<'EOF'
#!/usr/bin/env bash
# Ignore all args; simulate a short successful build.
sleep 1
echo "stub build ok"
EOF
chmod +x "$stub"

# Happy path: v8-debug with the stub gradle command.
RESULTS_DIR="$workdir/results" LABEL="unit" VARIANT="v8-debug" \
  NO_DAEMON="true" GRADLE_DAEMON_HEAP="4g" KOTLIN_DAEMON_HEAP="3g" \
  AAPT2_HEAP="2048M" WORKERS_MAX="4" PARALLEL="true" CLEAN_FIRST="false" \
  SAMPLE_INTERVAL="1" BENCH_GRADLE_CMD="bash $stub" \
  bash "$SUT"
rc=$?
[ "$rc" -eq 0 ] || { echo "FAIL: expected exit 0, got $rc"; exit 1; }

summary="$workdir/results/unit.summary.md"
[ -f "$summary" ]                       || { echo "FAIL: no summary file"; exit 1; }
grep -q "wall_clock_s"      "$summary"  || { echo "FAIL: no wall clock"; exit 1; }
grep -q "min_mem_available_mb" "$summary" || { echo "FAIL: no mem metric"; exit 1; }
grep -q "| exit_code | 0 |" "$summary"  || { echo "FAIL: exit_code not recorded"; exit 1; }

# Unknown variant must fail fast with code 2.
if RESULTS_DIR="$workdir/results" LABEL="bad" VARIANT="nope" \
     BENCH_GRADLE_CMD="bash $stub" bash "$SUT"; then
  echo "FAIL: unknown variant should have exited non-zero"; exit 1
fi
echo "PASS"

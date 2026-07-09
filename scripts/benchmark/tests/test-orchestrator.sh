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

# Regression (Fix 1): PEAK_HEAP with spaces (multi-daemon) must survive parsing.
multi="$(mktemp)"
printf '1\t9000000\tGradleDaemon:1=1048576\tKotlinCompileDaemon:2=2097152\n' > "$multi"
PEAK_HEAP="n/a"; MIN_AVAIL_MB=0
while IFS= read -r kv; do
  case "$kv" in
    MIN_AVAIL_MB=*) MIN_AVAIL_MB="${kv#MIN_AVAIL_MB=}" ;;
    PEAK_HEAP=*)    PEAK_HEAP="${kv#PEAK_HEAP=}" ;;
  esac
done < <(bash "$HERE/../summarize-samples.sh" "$multi")
[[ "$PEAK_HEAP" == *"GradleDaemon:1=1024MB"* && "$PEAK_HEAP" == *"KotlinCompileDaemon:2=2048MB"* ]] \
  || { echo "FAIL: multi-daemon PEAK_HEAP parse"; exit 1; }

echo "PASS"

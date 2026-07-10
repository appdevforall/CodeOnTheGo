#!/usr/bin/env bash
# Build-and-measure harness. Runs one parameterized Gradle APK build and records
# wall-clock + memory metrics so daemon/heap/parallelism configs can be compared.
set -uo pipefail

# Verbose command tracing for CI diagnosis. On by default; set BENCH_TRACE=0 to quiet.
# The background sampler disables it locally so the 2s loop doesn't flood the log.
BENCH_TRACE="${BENCH_TRACE:-1}"
[ "$BENCH_TRACE" = "1" ] && set -x

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VARIANT="${VARIANT:-v8-debug}"
# ENABLE_DAEMON=true reuses the Gradle daemon; anything else appends --no-daemon.
# Default false to reproduce the current production (--no-daemon) baseline.
ENABLE_DAEMON="${ENABLE_DAEMON:-false}"
GRADLE_DAEMON_HEAP="${GRADLE_DAEMON_HEAP:-8192M}"
KOTLIN_DAEMON_HEAP="${KOTLIN_DAEMON_HEAP:-8192M}"
AAPT2_HEAP="${AAPT2_HEAP:-8192M}"
WORKERS_MAX="${WORKERS_MAX:-30}"
PARALLEL="${PARALLEL:-true}"
CLEAN_FIRST="${CLEAN_FIRST:-false}"
SAMPLE_INTERVAL="${SAMPLE_INTERVAL:-2}"
RESULTS_DIR="${RESULTS_DIR:-benchmark-results}"
LABEL="${LABEL:-$(printf '%s_daemon-%s_gh-%s_kh-%s_ah-%s_w-%s_par-%s_clean-%s' \
  "$VARIANT" "$ENABLE_DAEMON" "$GRADLE_DAEMON_HEAP" "$KOTLIN_DAEMON_HEAP" \
  "$AAPT2_HEAP" "$WORKERS_MAX" "$PARALLEL" "$CLEAN_FIRST")}"

# Overridable so tests can inject a stub instead of the real (slow) build.
BENCH_GRADLE_CMD="${BENCH_GRADLE_CMD:-flox activate -d flox/base -- ./gradlew}"

mkdir -p "$RESULTS_DIR"
LOG_FILE="$RESULTS_DIR/${LABEL}.log"
SAMPLE_FILE="$RESULTS_DIR/${LABEL}.samples.tsv"
SUMMARY_FILE="$RESULTS_DIR/${LABEL}.summary.md"
: > "$SAMPLE_FILE"

# add-opens mirrors gradle.properties so overriding org.gradle.jvmargs stays valid.
ADD_OPENS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED"

declare -a GRADLE_ARGS
case "$VARIANT" in
  v8-debug)     GRADLE_ARGS=(":app:assembleV8Debug") ;;
  v7v8-release) GRADLE_ARGS=(":app:assembleV7Release" ":app:assembleV8Release") ;;
  *) echo "Unknown VARIANT: $VARIANT (want v8-debug|v7v8-release)" >&2; exit 2 ;;
esac

GRADLE_ARGS+=(
  "-Dorg.gradle.jvmargs=-Xmx${GRADLE_DAEMON_HEAP} -XX:+HeapDumpOnOutOfMemoryError ${ADD_OPENS}"
  "-Dkotlin.daemon.jvm.options=-Xmx${KOTLIN_DAEMON_HEAP}"
  "-Dandroid.aapt2.daemonHeapSize=${AAPT2_HEAP}"
  "-Dorg.gradle.workers.max=${WORKERS_MAX}"
  "-Dorg.gradle.parallel=${PARALLEL}"
)
[ "$ENABLE_DAEMON" = "true" ] || GRADLE_ARGS+=("--no-daemon")

if [ "$CLEAN_FIRST" = "true" ]; then
  echo "Cleaning before timed build..."
  # Clean mirrors the timed build's daemon mode (ENABLE_DAEMON). Forcing
  # --no-daemon here regardless would spawn a throwaway JVM instead of warming
  # the daemon the build reuses, and — with org.gradle.vfs.watch enabled — could
  # leave a reused warm daemon's file-watch cache stale vs. the deleted build/.
  # Stream output live; --console=plain + </dev/null so Gradle never waits on a
  # TTY or stdin. Don't hide it behind /dev/null — a hang here must be visible.
  clean_args=(clean --console=plain)
  [ "$ENABLE_DAEMON" = "true" ] || clean_args+=(--no-daemon)
  # shellcheck disable=SC2086
  $BENCH_GRADLE_CMD "${clean_args[@]}" </dev/null || true
fi

# NOTE: JVMs are matched by main-class name, not by process-tree ancestry, on
# purpose: a warm Gradle daemon is NOT a child of this gradlew invocation, so a
# parent-PID filter would make exactly the warm-daemon case we want to measure
# invisible. On the single-machine, builds-queue-one-at-a-time runner this is
# safe; on a shared dev box an unrelated IDE daemon could contaminate samples.
sample_memory() {
  { set +x; } 2>/dev/null  # keep the 2s sampling loop out of the xtrace stream
  while :; do
    local ts avail line pid main used
    ts="$(date +%s)"
    avail="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
    line="${ts}\t${avail}"
    if command -v jps >/dev/null 2>&1 && command -v jcmd >/dev/null 2>&1; then
      while read -r pid main; do
        case "$main" in
          *GradleDaemon*|*KotlinCompileDaemon*|*GradleWorkerMain*)
            used="$(jcmd "$pid" GC.heap_info 2>/dev/null \
              | awk -F'used ' 'NR==1 {split($2,a," "); gsub(/K/,"",a[1]); print a[1]+0}')"
            [ -n "$used" ] && [ "$used" != "0" ] && line="${line}\t${main##*.}:${pid}=${used}"
            ;;
        esac
      done < <(jps -l 2>/dev/null)
    fi
    printf '%b\n' "$line" >> "$SAMPLE_FILE"
    sleep "$SAMPLE_INTERVAL"
  done
}
sample_memory &
SAMPLER_PID=$!
trap 'kill "$SAMPLER_PID" 2>/dev/null || true' EXIT

START="$(date +%s)"
# Stream to the CI log AND capture to LOG_FILE (LOG_FILE feeds OOM detection).
# --console=plain + </dev/null prevent any wait on an interactive console/stdin.
# shellcheck disable=SC2086
$BENCH_GRADLE_CMD "${GRADLE_ARGS[@]}" --console=plain </dev/null 2>&1 | tee "$LOG_FILE"
EXIT_CODE=${PIPESTATUS[0]}
END="$(date +%s)"
DURATION=$((END - START))

kill "$SAMPLER_PID" 2>/dev/null || true
wait "$SAMPLER_PID" 2>/dev/null || true

# Derive memory metrics (unit-tested in Task 1).
MIN_AVAIL_MB=0
PEAK_HEAP="n/a"
while IFS= read -r kv; do
  case "$kv" in
    MIN_AVAIL_MB=*) MIN_AVAIL_MB="${kv#MIN_AVAIL_MB=}" ;;
    PEAK_HEAP=*)    PEAK_HEAP="${kv#PEAK_HEAP=}" ;;
  esac
done < <(bash "$SCRIPT_DIR/summarize-samples.sh" "$SAMPLE_FILE")

OOM="no"
if [ "$EXIT_CODE" -ne 0 ] && grep -qiE "OutOfMemoryError|Java heap space|GC overhead limit|Killed" "$LOG_FILE"; then
  OOM="yes"
fi

{
  echo "### Benchmark: \`${LABEL}\`"
  echo ""
  echo "| metric | value |"
  echo "|---|---|"
  echo "| variant | ${VARIANT} |"
  echo "| enable_daemon | ${ENABLE_DAEMON} |"
  echo "| gradle_daemon_heap | ${GRADLE_DAEMON_HEAP} |"
  echo "| kotlin_daemon_heap | ${KOTLIN_DAEMON_HEAP} |"
  echo "| aapt2_heap | ${AAPT2_HEAP} |"
  echo "| workers_max | ${WORKERS_MAX} |"
  echo "| parallel | ${PARALLEL} |"
  echo "| clean_first | ${CLEAN_FIRST} |"
  echo "| wall_clock_s | ${DURATION} |"
  echo "| exit_code | ${EXIT_CODE} |"
  echo "| oom_detected | ${OOM} |"
  echo "| min_mem_available_mb | ${MIN_AVAIL_MB} |"
  echo "| peak_heap_used | ${PEAK_HEAP} |"
} > "$SUMMARY_FILE"

cat "$SUMMARY_FILE"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$SUMMARY_FILE" >> "$GITHUB_STEP_SUMMARY"

exit "$EXIT_CODE"

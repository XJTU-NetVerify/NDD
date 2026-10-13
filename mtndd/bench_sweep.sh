#!/usr/bin/env bash
# bench_sweep.sh — Build and benchmark nqueens over (N, workers) matrix.
# Usage: ./bench_sweep.sh [--skip-build] [--n-min=7] [--n-max=13] [--w-min=1] [--w-max=6] [--parallel] [--pgo]
#
#   --parallel   Run mtpndd_nqueens_parallel_benchmark instead of the serial one.
#                (Outer-operation parallelism variant. Requires a working
#                parallel benchmark build; see note below.)
#   --pgo        Use build-pgo/ (produced by scripts/build-pgo.sh).
#                Run scripts/build-pgo.sh first.
#
# Layout (post 2026-04-21 migration):
#   build/mtpndd/mtpndd_nqueens_benchmark          — serial (default)
#   build/mtpndd/mtpndd_nqueens_parallel_benchmark — outer-parallel variant
#   build-pgo/mtpndd/...                           — PGO+LTO variant
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR_DEFAULT="$SCRIPT_DIR/build"
BUILD_DIR_PGO="$SCRIPT_DIR/build-pgo"

N_MIN=7
N_MAX=13
W_MIN=1
W_MAX=6
SKIP_BUILD=0
USE_PARALLEL=0
USE_PGO=0

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --parallel)   USE_PARALLEL=1 ;;
        --pgo)        USE_PGO=1 ;;
        --n-max=*)    N_MAX="${arg#*=}" ;;
        --w-max=*)    W_MAX="${arg#*=}" ;;
        --n-min=*)    N_MIN="${arg#*=}" ;;
        --w-min=*)    W_MIN="${arg#*=}" ;;
    esac
done

if [[ "$USE_PGO" -eq 1 ]]; then
    BUILD_DIR="$BUILD_DIR_PGO"
    BUILD_LABEL="pgo"
else
    BUILD_DIR="$BUILD_DIR_DEFAULT"
    BUILD_LABEL="release"
fi

if [[ "$USE_PARALLEL" -eq 1 ]]; then
    BENCH_BIN="$BUILD_DIR/mtpndd/mtpndd_nqueens_parallel_benchmark"
    BENCH_LABEL="parallel"
else
    BENCH_BIN="$BUILD_DIR/mtpndd/mtpndd_nqueens_benchmark"
    BENCH_LABEL="serial"
fi

# ── Build ──────────────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" -eq 0 && "$USE_PGO" -eq 0 ]]; then
    echo "[bench_sweep] Configuring (Release default, LOG_LEVEL=1)..."
    (cd "$SCRIPT_DIR" && cmake -B build -DMTPNDD_LOG_LEVEL=1 >/dev/null)
    echo "[bench_sweep] Building mtpndd_nqueens_benchmark..."
    (cd "$SCRIPT_DIR" && cmake --build build --target mtpndd_nqueens_benchmark -j >/dev/null)
    if [[ "$USE_PARALLEL" -eq 1 ]]; then
        echo "[bench_sweep] Building mtpndd_nqueens_parallel_benchmark..."
        (cd "$SCRIPT_DIR" && cmake --build build --target mtpndd_nqueens_parallel_benchmark -j 2>/dev/null) || {
            echo "[bench_sweep] ERROR: parallel benchmark failed to build." >&2
            exit 1
        }
    fi
fi

if [[ "$USE_PGO" -eq 1 && ! -x "$BENCH_BIN" ]]; then
    echo "ERROR: PGO build not found. Run scripts/build-pgo.sh first." >&2
    exit 1
fi

if [[ ! -x "$BENCH_BIN" ]]; then
    echo "ERROR: benchmark binary not found at $BENCH_BIN" >&2
    exit 1
fi

# ── Collect results ────────────────────────────────────────────────────────────
RESULT_FILE="$SCRIPT_DIR/bench_results_${BENCH_LABEL}_${BUILD_LABEL}_$(date +%Y%m%d_%H%M%S).tsv"
printf "N\tworkers\telapsed_s\tsolutions\n" > "$RESULT_FILE"

printf "%-4s  %-7s  %-12s  %-12s\n" "N" "workers" "elapsed(s)" "solutions"
printf "%s\n" "----  -------  ------------  ------------"

for n in $(seq "$N_MIN" "$N_MAX"); do
    for w in $(seq "$W_MIN" "$W_MAX"); do
        raw=$("$BENCH_BIN" "$n" "$w" 2>/dev/null || true)
        # output format: \t<elapsed>\t<solutions>  (leading tab, skip stats lines)
        result_line=$(printf '%s\n' "$raw" | grep -v '^\[' | grep -v '^\.\.' | head -1)
        elapsed=$(printf '%s\n' "$result_line" | awk '{print $1}')
        solutions=$(printf '%s\n' "$result_line" | awk '{print $2}')

        if [[ -z "$elapsed" ]]; then
            elapsed="ERR"
            solutions="ERR"
        fi

        printf "%d\t%d\t%s\t%s\n" "$n" "$w" "$elapsed" "$solutions" >> "$RESULT_FILE"
        printf "%-4d  %-7d  %-12s  %-12s\n" "$n" "$w" "$elapsed" "$solutions"
    done
done

echo ""
echo "[bench_sweep] Results saved to: $RESULT_FILE"

#!/usr/bin/env bash
# Build MTPNDD with Profile-Guided Optimization + LTO.
#
# Two-step process:
#   1. Build with -fprofile-generate, run a representative workload
#      (nqueens sweep) to collect .gcda files.
#   2. Rebuild with -fprofile-use, reusing the same build directory so
#      the embedded file paths in .gcda match.
#
# Measured gain over plain Release build on mtpndd_nqueens_benchmark
# (N=10..13, W=1..6):
#   W=1: -8 to -12% (largest, single-thread path benefits most)
#   W=2: -8 to -9%
#   W=4: -5 to -7%
#   W=6: -1 to -5%
# Geometric mean ≈ -7%.

set -euo pipefail

BUILD_DIR="${BUILD_DIR:-build-pgo}"
PROFILE_DIR="${PROFILE_DIR:-/tmp/mtpndd-pgo-data}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

echo "==> [1/3] Configure + build with -fprofile-generate"
rm -rf "$BUILD_DIR"
mkdir -p "$PROFILE_DIR"
cmake -B "$BUILD_DIR" \
    -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG -fprofile-generate=$PROFILE_DIR -flto" \
    -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG -fprofile-generate=$PROFILE_DIR -flto" \
    -DCMAKE_EXE_LINKER_FLAGS="-fprofile-generate=$PROFILE_DIR -flto" \
    -DCMAKE_SHARED_LINKER_FLAGS="-fprofile-generate=$PROFILE_DIR -flto"
cmake --build "$BUILD_DIR" --target mtpndd_nqueens_benchmark -j

BENCH="$BUILD_DIR/mtpndd/mtpndd_nqueens_benchmark"

echo "==> [2/3] Run training workload (nqueens sweep N=10..12, W=1,4,6)"
for N in 10 11 12; do
    for W in 1 4 6; do
        echo "    training: N=$N W=$W"
        "$BENCH" $N $W >/dev/null 2>&1
    done
done

echo "==> [3/3] Rebuild with -fprofile-use in the same directory"
cmake -B "$BUILD_DIR" \
    -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG -fprofile-use=$PROFILE_DIR -fprofile-correction -Wno-missing-profile -flto" \
    -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG -fprofile-use=$PROFILE_DIR -fprofile-correction -Wno-missing-profile -flto" \
    -DCMAKE_EXE_LINKER_FLAGS="-fprofile-use=$PROFILE_DIR -flto" \
    -DCMAKE_SHARED_LINKER_FLAGS="-fprofile-use=$PROFILE_DIR -flto"
cmake --build "$BUILD_DIR" -j

echo ""
echo "==> Done. Optimized binaries in $BUILD_DIR/"
echo "    Profile data retained in $PROFILE_DIR"
echo "    Verify with: $BENCH 12 6"

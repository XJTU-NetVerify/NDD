#!/usr/bin/env bash
# Run only after both repositories have been built. Fresh output is mandatory.
set -euo pipefail
NDD_REPO="${NDD_REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
SRE_REPO="${SRE_REPO:-$(dirname "$NDD_REPO")/SRE}"
: "${JAVA_CONTROL_CLASSES:?Set JAVA_CONTROL_CLASSES to frozen original SRE target/classes}"
: "${JAVA_CONTROL_JAR:?Set JAVA_CONTROL_JAR to frozen fixed-ndd.jar}"
: "${NATIVE_LIBRARY:?Set NATIVE_LIBRARY to the matching new libpnddjni.so}"
: "${OUTPUT_ROOT:?Set OUTPUT_ROOT to a new result directory}"
if [[ -e "$OUTPUT_ROOT" ]]; then
    echo "Choose a new OUTPUT_ROOT; historical data must not be overwritten." >&2
    exit 2
fi
mkdir -p "$OUTPUT_ROOT"
OUTPUT_ROOT="$(cd "$OUTPUT_ROOT" && pwd)"
cd "$SRE_REPO"
for size in 4 8 12; do
    case "$size" in
        4) cache=262144 ;;
        8) cache=4194304 ;;
        12) cache=16777216 ;;
    esac
    printf -v directory 'final%02d' "$size"
    python3 experiments/parallel-ndd/benchmark.py \
        --ndd "$NDD_REPO" --library "$NATIVE_LIBRARY" \
        --java-classes "$JAVA_CONTROL_CLASSES" --java-jar "$JAVA_CONTROL_JAR" \
        --output "$OUTPUT_ROOT/$directory" --sizes "$size" --mfs 1,2,3 \
        --cache "$cache" --engines java,native1,native2,native6 --repeats 2 \
        --cpus "${BENCH_CPUS:-0,2,4,6,8,10}"
done
python3 "$NDD_REPO/native/scripts/summarize_sre_deep.py" "$OUTPUT_ROOT"

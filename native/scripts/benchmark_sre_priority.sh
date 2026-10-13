#!/usr/bin/env bash
# Build matching current artifacts and freeze the old control before running.
set -euo pipefail
NDD_REPO="${NDD_REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
SRE_REPO="${SRE_REPO:-$(dirname "$NDD_REPO")/SRE}"
: "${JAVA_CONTROL_CLASSES:?Set JAVA_CONTROL_CLASSES to frozen original SRE classes}"
: "${JAVA_CONTROL_JAR:?Set JAVA_CONTROL_JAR to frozen fixed Java NDD JAR}"
: "${NATIVE_LIBRARY:?Set NATIVE_LIBRARY to the matching current JNI library}"
: "${OLD_NATIVE_LIBRARY:?Set OLD_NATIVE_LIBRARY to the frozen previous JNI library}"
: "${OLD_NATIVE_CLASSES:?Set OLD_NATIVE_CLASSES to its matching frozen SRE classes}"
: "${OLD_NATIVE_JAR:?Set OLD_NATIVE_JAR to its matching frozen NDD JAR}"
: "${OUTPUT_ROOT:?Set OUTPUT_ROOT to a new result directory}"
if [[ -e "$OUTPUT_ROOT" ]]; then
    echo "Choose a new OUTPUT_ROOT; preserve historical results." >&2
    exit 2
fi
mkdir -p "$OUTPUT_ROOT"
OUTPUT_ROOT="$(cd "$OUTPUT_ROOT" && pwd)"
cd "$SRE_REPO"
common=(--ndd "$NDD_REPO" --java "${BENCH_JAVA:-java}"
        --java-classes "$JAVA_CONTROL_CLASSES" --java-jar "$JAVA_CONTROL_JAR"
        --cpus "${BENCH_CPUS:-0,2,4,6,8,10}" --repeats 2)
for size in 8 12; do
    if [[ "$size" == 8 ]]; then
        prefix=first08; cache=4194304; engines=java,native1,native6
    else
        prefix=large12; cache=16777216; engines=java,native1,native2,native6
    fi
    case_args=(--sizes "$size" --mfs 3 --cache "$cache" --gc-high-water-mib 1228)
    python3 experiments/parallel-ndd/benchmark.py "${common[@]}" "${case_args[@]}" \
        --library "$NATIVE_LIBRARY" --engines "$engines" --priority-lists true \
        --priority-list-block 64 --output "$OUTPUT_ROOT/$prefix-on"
    python3 experiments/parallel-ndd/benchmark.py "${common[@]}" "${case_args[@]}" \
        --library "$NATIVE_LIBRARY" --engines native1,native6 --priority-lists false \
        --output "$OUTPUT_ROOT/$prefix-off"
    python3 experiments/parallel-ndd/benchmark.py "${common[@]}" "${case_args[@]}" \
        --library "$OLD_NATIVE_LIBRARY" --native-classes "$OLD_NATIVE_CLASSES" \
        --native-jar "$OLD_NATIVE_JAR" --engines native1,native6 --priority-lists false \
        --output "$OUTPUT_ROOT/$prefix-old"
done
# Other cases check the new default path against the archived semantic matrix.
for size in 4 8 12; do
    case "$size" in
        4) cache=262144; mfs=1,2,3 ;;
        8) cache=4194304; mfs=1,2 ;;
        12) cache=16777216; mfs=1,2 ;;
    esac
    python3 experiments/parallel-ndd/benchmark.py "${common[@]}" --sizes "$size" \
        --mfs "$mfs" --cache "$cache" --library "$NATIVE_LIBRARY" --engines native6 \
        --priority-lists true --priority-list-block 64 --output "$OUTPUT_ROOT/regression$size"
done
python3 "$NDD_REPO/native/scripts/summarize_sre_priority.py" "$OUTPUT_ROOT"

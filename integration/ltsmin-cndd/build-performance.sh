#!/usr/bin/env bash
# Same pinned frontend and core; opt in to the measured, uninstrumented preset.
set -euo pipefail
export LTSMIN_BUILD_MODE=performance
export CNDD_IMAGE_PAIR_CACHE=ON
exec bash "$(dirname "$0")/build.sh" "$@"

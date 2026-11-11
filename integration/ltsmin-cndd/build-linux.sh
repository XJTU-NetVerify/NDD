#!/usr/bin/env bash
# Compatibility entry point; the pinned upstream build also supports macOS.
set -euo pipefail
exec bash "$(dirname "$0")/build.sh" "$@"

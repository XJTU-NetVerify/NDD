#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
c4_work=${1:?Usage: build.sh NEW_WORK_DIRECTORY PINNED_UPSTREAM_TREE CMAKE SYLVAN_SOURCE LACE_SOURCE}
upstream=${2:?}; c4_cmake=${3:?}; sylvan=${4:?}; lace=${5:?}
if [[ -e "$c4_work" ]]; then
  echo "Use a new work directory: $c4_work" >&2
  exit 2
fi
mkdir -p "$c4_work/logs"
build_mode=${LTSMIN_BUILD_MODE:-diagnostic}
build_jobs=${LTSMIN_BUILD_JOBS:-1}
image_cache=${CNDD_IMAGE_PAIR_CACHE:-OFF}
[[ "$image_cache" == ON || "$image_cache" == OFF ]] || { echo "CNDD_IMAGE_PAIR_CACHE must be ON or OFF" >&2; exit 2; }
[[ "$build_mode" == diagnostic || "$build_mode" == performance ]] || exit 2
[[ "$build_jobs" =~ ^[1-9][0-9]*$ ]] || exit 2
if [[ $(uname -s) == Darwin ]]; then
  c4_brew=$(brew --prefix)
  export PATH="$c4_brew/opt/bison/bin:$PATH"
  export CPPFLAGS="${CPPFLAGS:-} -I$c4_brew/include"
  export LDFLAGS="${LDFLAGS:-} -L$c4_brew/lib"
  export PKG_CONFIG_PATH="$c4_brew/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
fi
"$c4_cmake" -S "$repo/native" -B "$c4_work/core" -DCMAKE_BUILD_TYPE=Release -DPNDD_BUILD_JNI=OFF -DPNDD_LTO=OFF -DPNDD_MC_IMAGE_PAIR_CACHE="$image_cache" -DBUILD_TESTING=OFF -DPNDD_SYLVAN_SOURCE="$sylvan" -DFETCHCONTENT_SOURCE_DIR_LACE="$lace" > "$c4_work/logs/core-configure.log" 2>&1
"$c4_cmake" --build "$c4_work/core" --target pndd -j "$build_jobs" > "$c4_work/logs/core-build.log" 2>&1
python3 "$repo/integration/ltsmin-cndd/prepare.py" --base "$upstream" --out "$c4_work/ltsmin-src" --mode "$build_mode" > "$c4_work/logs/prepare.log" 2>&1
export CNDD_CORE_CPPFLAGS="-I$repo/native/include"
export CNDD_CORE_LIBS="$c4_work/core/libpndd.a $c4_work/core/sylvan-build/src/libsylvan.a $c4_work/core/_deps/lace-build/liblace.a -lpthread -lm"
(cd "$c4_work/ltsmin-src" && autoreconf -fi) > "$c4_work/logs/autoreconf.log" 2>&1
mkdir -p "$c4_work/ltsmin-build"
(cd "$c4_work/ltsmin-build" && "$c4_work/ltsmin-src/configure" --disable-dist --disable-scoop --disable-opaal --disable-prob --disable-pnml --without-spins --without-spot --without-sylvan --without-buddy CC=gcc CXX=g++ CFLAGS='-O2 -g -fcommon -std=gnu11' CXXFLAGS='-O2 -g') > "$c4_work/logs/configure.log" 2>&1
make -C "$c4_work/ltsmin-build" -j "$build_jobs" > "$c4_work/logs/build.log" 2>&1
make -C "$c4_work/ltsmin-build/src/tests" test-cndd -j "$build_jobs" > "$c4_work/logs/test-build.log" 2>&1

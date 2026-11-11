# C-NDD / LTSmin bridge

This extension adds `--vset=cndd` to LTSmin master snapshot
`07f9bf8aa8957cab4f8a47c1a943bf7fef968288` (3.1.0-dev, not a release).
[upstream.json](upstream.json) locks the archives, source tree and ltl2ba
submodule; it is not a claim about today's latest upstream revision.

The native C11 engine provides diagram operations, `pndd_mc.h` adds logical
state sets/relations, and `vset_cndd.c` implements vdom/vset/vrel callbacks.
LTSmin retains its model frontend, dependency regrouping and BFS loop.
See [implementation notes](../../doc/model-checking.md).

## Build and validate

Requirements: C/C++ compilers, CMake3.23+, Python3, Git, Autoconf/Automake/Libtool,
Bison>=3.0.2, Flex, pkg-config, popt and GMP. macOS uses Homebrew prefixes.
Supply Git checkouts of Sylvan `c8bf561ee64036375f4693eb389e57ec8ef4aa6d`
and Lace `63d8a5ea79922a234e284ee4b264fa5e4e784939`.
The build modifies private copies of dependencies and the frontend.

```sh
python3 integration/ltsmin-cndd/fetch-upstream.py --out /absolute/ltsmin-pristine
bash integration/ltsmin-cndd/build.sh \
  /absolute/cndd-diagnostic /absolute/ltsmin-pristine \
  /absolute/cmake /absolute/sylvan /absolute/lace
python3 integration/ltsmin-cndd/validate.py \
  --work /absolute/cndd-diagnostic --out /absolute/diagnostic-results
```

Work/output directories must be new. Diagnostic is the default mode; it includes
optional per-layer tuple tracing. The frontend is built without the reference
Sylvan/BuDDy backends: C-NDD owns its single runtime. Native ListDD remains a
semantic comparison. The validator checks callbacks at workers1/2/4, GC0/11,
cleanup and gear.1 (2689 states) against ListDD including layer tuples.

On Linux, custom plugins can be supplied with `--model /path/model.dve2C
--expected-states N`. Reusing a plugin retains its hash; it is not a rebuild.
ELF DVE plugins cannot run on macOS. build-linux.sh/validate-linux.py are aliases.

## Performance preset

```sh
bash integration/ltsmin-cndd/build-performance.sh \
  /absolute/cndd-performance /absolute/ltsmin-pristine \
  /absolute/cmake /absolute/sylvan /absolute/lace
taskset -c 0 /absolute/cndd-performance/ltsmin-build/src/pins2lts-sym/dve2lts-sym \
  --vset=cndd --cndd-workers=1 --cndd-memory-mib=1024 \
  --cndd-cache-log2=20 --cndd-gc-interval=0 \
  --order=bfs-prev -rgs --when /absolute/model.dve2C
```

The wrapper sets `LTSMIN_BUILD_MODE=performance` and `CNDD_IMAGE_PAIR_CACHE=ON`.
The build passes `-DPNDD_MC_IMAGE_PAIR_CACHE=ON` to CMake and omits BFS tracing;
profiling and LTO remain off. Generic core and diagnostic builds default the
image-pair cache to OFF. Normal CLI defaults remain64MiB/cache12; explicit
flags above select the measured configuration. CPU0 must be available on the
Linux host. The example does not implement the full monitored600s/80GiB harness.

Validate tuples with a separate diagnostic build. Performance binaries omit
the tracing hook and must not be used with the tuple-comparison validator.
See the [snapshot and limits](../../results/model_checking_20261105/README.md).

## Scope and lifecycle

One active domain per process,0–256 slots,unsigned16-bit values. Boolean state
sets/relations use BCDD field labels; tuples follow projection order. Forward
image, set operations, projection/join, enumeration/update callbacks and separate
read/write/COPY relations are supported. Predecessor, saturation, persistence
and numeric terminals are outside this bridge. No unsupported operation should
be interpreted as an empty result.

GC interval0 disables periodic MC collection, not capacity-driven or Sylvan GC.
Normal exit checks baseline live nodes. The recorded cambridge.7 internal
capacity failure also produced an exit-cleanup error; the abrupt-exit path is a
known limitation, not a proven normal-path leak.

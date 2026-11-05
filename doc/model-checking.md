# Model-checking extension: implementation boundary

The NDD representation, canonical node engine, generic Boolean operations, label BCDD engine and Lace runtime are inherited from the native C-NDD implementation. The extension adds tuple/domain semantics and an LTSmin adapter. LTSmin continues to own the frontend, dependency processing and BFS loop.

## Native MC layer

`native/include/pndd_mc.h` exposes opaque domain, set and relation objects. `native/src/pndd_mc_impl.inc` implements state-set construction, membership, enumeration, counting, projection and compatible joins. These have logical tuple/projection semantics; generic physical-domain satisfiability counting is not a substitute.

`native/src/pndd_mc_relation_impl.inc` handles separate read/write projections and COPY transitions. Current/next bits are paired within field labels, with a relation-scope bit distinguishing an explicitly unconstrained relation field from an omitted field that must preserve the source value.

`native/src/pndd_mc_relnext_impl.inc` computes forward image while preserving cross-field path correlations and identity on omitted relation fields. A full-image pair cache is enabled in the measured build; it is not the generic source default. All three implementation fragments are compiled through `native/src/pndd.c` and depend on that file's internal structures; they are not standalone libraries.

Inputs are borrowed and successful results are owned. Errors/invalid handles are distinct from the empty set. Roots, enumeration snapshots, temporary labels and GC lifetimes are part of the MC contract.

## LTSmin adapter and build wiring

`integration/ltsmin-cndd/vset_cndd.c` implements the existing vdom/vset/vrel callbacks and exposes `--vset=cndd`. It translates compact projected tuples through projection indices into state-slot/field indices, implements forward image and registers options. It does not replace BFS or make the entire state set a global BDD.

`prepare.py` applies registration, configure and link changes to a checksum-locked pristine LTSmin tree. Relevant edits include vector_set.h/c, vset-lib/Makefile.am, configure.ac and all libvset consumers. Diagnostic builds additionally insert layer tracing; performance builds omit it. Only one Sylvan/Lace runtime owns the C-NDD checker. The comparison reference checker is built separately.

Supported scope is one active domain with 0–256 slots and unsigned 16-bit values, Boolean state sets, BCDD field labels and forward reachability. Multiple workers have correctness tests, but this benchmark measures one. Predecessor, saturation, persistence and numeric-terminal model checking are not claimed.

This checkout includes the inherited core, MC layer, adapter, pinned dependency preparation and semantic tests. See integration/ltsmin-cndd/README.md for diagnostic and performance build entry points. The separate four-backend reference build still requires the archived baseline inputs; see the result report.

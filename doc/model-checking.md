# Model-checking extension: implementation boundary

The NDD representation, canonical node engine, generic Boolean operations, label BCDD engine and Lace runtime are inherited from the native C-NDD implementation. The extension adds tuple/domain semantics and an LTSmin adapter. LTSmin continues to own the frontend, dependency processing and BFS loop.

## Why the field structure fits model checking

NDD’s field-oriented representation naturally matches the state-vector structure used in symbolic model checking. The outer diagram keeps state variables explicit as fields; labels represent sets of values within a field. When several values have the same continuation, an edge can group them symbolically and share the remaining diagram. BCDD labels supply bit-level Boolean constraints inside each field, while the outer NDD retains the tuple structure.

Grouped transitions expose read/write projections over state variables. For example, a state `(pc, x, y)` may have a transition that reads `pc` and `x`, updates `x`, and leaves `y` unchanged. The MC representation records the affected fields and preserves identity on the omitted field. It also preserves dependencies between fields: grouping values is not equivalent to independently unioning each variable's possible values.

This provides the structural motivation for the integration: field-level state sharing, field-local symbolic constraints and projected transition semantics can be expressed together in one representation. The benchmark evaluates the resulting implementation through LTSmin's existing exploration loop.

## Frontend-to-core data path

1. LTSmin exposes state-vector slots and transition-group read/write projections.
2. The C-NDD domain maps slot `i` to field `i`, using zero-based indices. This differs from the old MTPNDD adapter's `i + 1` convention. Logical values are unsigned 16-bit; current, next and relation-scope variables are internal label metadata.
3. State insertion constructs an exact tuple in projection order and unions it into the symbolic set. Relation insertion records READ, WRITE and COPY modes and unions the resulting relation path.
4. During BFS, the adapter implements projection, join, set operations and forward image through the MC API. `set_next` delegates to `pndd_mc_set_next`; LTSmin retains the frontier/visited fixed-point algorithm.
5. MC objects own their roots and retain enumeration snapshots across callbacks. Failed operations preserve the contracts documented in `pndd_mc.h`; the empty set is not an error sentinel.

| MC operation | API |
| --- | --- |
| Domain and projection-aware sets | `pndd_mc_open`, `pndd_mc_set_create` |
| Exact state tuples and membership | `pndd_mc_set_add`, `pndd_mc_set_member` |
| Set union, intersection and difference | `pndd_mc_set_union`, `pndd_mc_set_intersect`, `pndd_mc_set_minus` |
| Projection and compatible join | `pndd_mc_set_project`, `pndd_mc_set_project_minus`, `pndd_mc_set_join` |
| Enumeration, example and logical counting | `pndd_mc_set_enum`, `pndd_mc_set_example`, `pndd_mc_set_count` |
| Separate read/write relations and COPY | `pndd_mc_rel_create_rw`, `pndd_mc_rel_add`, `pndd_mc_rel_add_cpy` |
| Relation tuple counting | `pndd_mc_rel_count` |
| Forward relational image | `pndd_mc_set_next` |

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

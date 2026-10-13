# Print DOT Path Support + GC Roots Cleanup Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add optional file-path output to `mtpndd_print_dot` and remove unused GC root collection helpers.

**Architecture:** `mtpndd_print_dot` becomes the single public entry point; it attempts file output when a path is provided, otherwise falls back to stdout. `mtpndd_fprint_dot` remains a lower-level writer, but all in-repo call sites use `mtpndd_print_dot`. GC root stubs are removed and `gc_internal()` no longer allocates/release empty root lists.

**Tech Stack:** C11, libc stdio, existing MTPNDD codebase.

## Notes
- **TDD is explicitly skipped by user request** (“不需要TDD”).
- Any JNI changes are only needed if Java API exposes DOT output; current repo has no print-dot JNI bindings.

### Task 1: Update public API for print-dot

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_node.h`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_node.c`

**Step 1: Update header signature**
Change `mtpndd_print_dot` to accept optional `const char *path`.

```c
void mtpndd_print_dot(mtpndd_t *root, const char *path);
```

**Step 2: Implement file-or-stdout behavior**
In `mtpndd_node.c`, update `mtpndd_print_dot`:
- If `path != NULL` and `path[0] != '\0'`, attempt `fopen(path, "w")`.
- On success: write via `mtpndd_fprint_dot`, then `fclose`.
- On failure: `perror("fopen dot file")` and fall back to stdout.
- If no usable path: write to stdout.

### Task 2: Update call sites to use `mtpndd_print_dot`

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/test/nqueens.c`

**Step 1: Replace direct `mtpndd_fprint_dot` usage**
In `dump_dot_file`, replace:

```c
FILE *out = fopen(path, "w");
...
mtpndd_fprint_dot(out, node);
```

with a single call:

```c
mtpndd_print_dot(node, path);
```

Keep the directory creation logic; the new `mtpndd_print_dot` handles file open failure and stdout fallback.

### Task 3: Remove unused GC root helpers

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.c`

**Step 1: Remove stubs and calls**
- Delete `mtpndd_gc_collect_roots` and `mtpndd_gc_release_roots` static functions.
- Remove their invocation from `gc_internal()`.

### Task 4: Minimal verification (no formal tests)

**Step 1: Build (optional)**
Run: `cd sylvan && cmake -B build && cmake --build build`

**Step 2: Manual smoke**
Run: `./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 4` (or existing workflow) and confirm DOT output still appears when enabled.

### Task 5: Commit

```bash
git add sylvan/src/sylvan/mtpndd/mtpndd_node.h \
        sylvan/src/sylvan/mtpndd/mtpndd_node.c \
        sylvan/src/sylvan/mtpndd/mtpndd_nodetable.c \
        sylvan/src/sylvan/mtpndd/test/nqueens.c \
        docs/plans/2026-01-28-print-dot-gc-roots-plan.md

git commit -m "refactor(mtpndd): update print_dot and drop gc root stubs"
```

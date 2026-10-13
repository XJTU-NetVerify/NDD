# Lace Idle + Leapfrog Backoff (Reduce Busy-Spin Contention)

**Date:** 2026-01-28
**Status:** worktree change (not yet recorded as a git commit at the time of writing)
**Goal:** Reduce wasted CPU and cache-line contention when Lace workers are idle or when a worker is waiting for a stolen task, improving multi-worker scaling stability.

## Motivation / Symptom

Lace's work-stealing runtime can spend significant CPU time in:
- the idle steal loop (workers polling for work), and
- `lace_leapfrog` (a worker waiting for a thief to finish a stolen task).

In practice, excessive busy spinning can:
- increase memory subsystem contention (hurting the “useful” workers),
- increase context switching pressure, and
- make “more workers” slower even for workloads that should scale moderately (<=4 cores).

## Root Cause (High-level)

The default behavior in the idle path is to poll aggressively for work:
- `lace_steal_loop` repeatedly attempts steals; without backoff, this can become tight spinning when no work is available.
- `lace_leapfrog` can retry steal attempts while “waiting”, also turning into busy-wait loops.

## Implementation Approach

**Design:** Add a small adaptive backoff when no work is available.

Changes:
- In `lace_steal_loop`:
  - add an idle backoff on the `LACE_NOWORK` path (and guard single-worker case to avoid invalid RNG ranges).
- In `lace_leapfrog`:
  - add a backoff on the `LACE_NOWORK` path to avoid tight retry loops when waiting.
- Add configuration defaults in `lace_config.h` to control yield/sleep thresholds.
- Fix Lace counters reporting to avoid division-by-zero when steals/leaps are zero.

**Files changed:**
- `sylvan/src/sylvan/lace.c`
- `sylvan/src/sylvan/lace.h`
- `sylvan/src/sylvan/lace_config.h`

## Verification

Commands:
```bash
cd sylvan
ctest --test-dir build
taskset -c 0-3 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

Expected:
- Functional correctness unchanged (`solutions=14200` for `n=12`).

## Evidence / Observability

Syscall-level signal (example, `strace -f -c`):
- Presence of `sched_yield` and `clock_nanosleep` during multi-worker runs indicates workers are not pure busy-spin under no-work conditions.
- This reduces the time spent in kernel futex wakeups attributable to lock contention in other layers by lowering background interference.

Note: exact syscall counts depend heavily on workload shape, worker count, and task-stealing dynamics.

## Risks / Follow-ups

- Too-aggressive sleeping can reduce throughput if workers back off while work is about to appear; tuning thresholds matters.
- Follow-up: expose backoff parameters via build-time or runtime configuration and sweep for best settings on target machines.

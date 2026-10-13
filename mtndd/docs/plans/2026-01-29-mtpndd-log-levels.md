# MTPNDD Log Levels (QUIET/INFO/DEBUG) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace `ENABLE_RECORDING` with compile-time log levels and standardize output via macros.

**Architecture:** Introduce `MTPNDD_LOG_LEVEL` (0=QUIET, 1=INFO, 2=DEBUG) in `mtpndd_common.h`, replace `#ifdef ENABLE_RECORDING` with `#if MTPNDD_LOG_LEVEL >= 2`, and wrap output in logging macros. Keep ERROR logs always enabled. INFO logs include GC start/end; DEBUG enables stats/timing. Update CMake + docs to use `MTPNDD_LOG_LEVEL`.

**Tech Stack:** C (MTPNDD/Sylvan), JNI C, CMake.

### Task 1: Define log-level macros and update core headers

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_common.h`

**Step 1: Add log level constants + default**
Add `#ifndef MTPNDD_LOG_LEVEL` default to 1. Define `MTPNDD_LOG_ERROR`, `MTPNDD_LOG_INFO`, `MTPNDD_LOG_DEBUG` macros.

**Step 2: Replace ENABLE_RECORDING guards**
Change the stats/recording sections to `#if MTPNDD_LOG_LEVEL >= 2`.

### Task 2: Update CMake options

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/CMakeLists.txt`
- Modify: `jni/CMakeLists.txt`

**Step 1: Replace MTPNDD_ENABLE_RECORDING with MTPNDD_LOG_LEVEL**
Add cache var `MTPNDD_LOG_LEVEL` (default 1) and compile definition `MTPNDD_LOG_LEVEL=<value>` for both mtpndd and JNI.

**Step 2: No compatibility layer**
Remove `MTPNDD_ENABLE_RECORDING`; all builds use `MTPNDD_LOG_LEVEL`.

### Task 3: Replace ENABLE_RECORDING usage in code

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_common.c`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.c`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_node.c`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_operation_cache.c`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.h`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.h`
- Modify: `jni/src/main/native/mtpndd_jni.c`
- Modify tests under `sylvan/src/sylvan/mtpndd/test/`

**Step 1: Mechanical macro switch**
Replace `#ifdef ENABLE_RECORDING` with `#if MTPNDD_LOG_LEVEL >= 2` and update trailing comments.

**Step 2: Restore debug prints in nodetable**
Wrap previous `[MTPNDD DEBUG]` logs in `MTPNDD_LOG_DEBUG`.

**Step 3: Convert error prints to MTPNDD_LOG_ERROR**
Replace `fprintf(stderr, "[MTPNDD ERROR] ...")` with `MTPNDD_LOG_ERROR` where applicable.

### Task 4: Update docs and build commands

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/PERFORMANCE_ANALYSIS.md`
- Modify: `sylvan/docs/PERFORMANCE_ANALYSIS.md`
- Modify: `docs/archived/perf/efficiency_hypotheses.md`
- Modify: `docs/archived/perf/slab_pool_tune_{A,B,C}.md`
- Modify: `docs/plans/2026-01-28-parallel-throughput-per-worker-pools-and-coarser-and.md`

**Step 1: Replace MTPNDD_ENABLE_RECORDING references**
Use `-DMTPNDD_LOG_LEVEL=2` for debug/recording runs; `1` for release.

### Task 5: Build verification

**Step 1: Build with default INFO**
Run: `cd sylvan && cmake -B build && cmake --build build`
Expected: build succeeds.

**Step 2: Build with DEBUG**
Run: `cd sylvan && cmake -B build -DMTPNDD_LOG_LEVEL=2 && cmake --build build`
Expected: build succeeds.

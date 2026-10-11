#!/usr/bin/env python3
"""Adapt a private copy of pinned Lace for oversubscribed worker pools."""
import shutil
import sys
from pathlib import Path

source, destination = map(Path, sys.argv[1:])
shutil.copytree(source, destination, dirs_exist_ok=True,
                ignore=shutil.ignore_patterns('.git', 'build', '__pycache__'))
p = destination / 'src/lace.c'
s = p.read_text()
# A preempted worker cannot reach a GC barrier while other workers occupy all
# CPUs spinning. Preserve the upstream fast path when every worker has a CPU.
old = '// possibly pause/yield'
if s.count(old) != 2:
    raise SystemExit('Pinned Lace barrier wait sites have changed')
s = s.replace(old, 'if (n_workers > n_pus) lace_sleep_us(50);')
old = 'while (atomic_load_explicit(finished, memory_order_relaxed) != 0) STEAL_RANDOM();'
if s.count(old) != 1:
    raise SystemExit('Pinned Lace TOGETHER completion wait has changed')
s = s.replace(old, '''while (atomic_load_explicit(finished, memory_order_relaxed) != 0) {
        STEAL_RANDOM();
        if (n_workers > n_pus) lace_sleep_us(50);
    }''')
p.write_text('/* PNDD adaptation: bounded sleep in oversubscribed GC waits. */\n' + s)
# sched_getaffinity is a GNU extension. The upstream check omits its feature
# macro and falls back to the host CPU count, ignoring taskset/container masks.
p = destination / 'CMakeLists.txt'
s = p.read_text(encoding='utf-8-sig')
old = 'check_symbol_exists(sched_getaffinity "sched.h" HAVE_SCHED_GETAFFINITY)'
if s.count(old) != 1:
    raise SystemExit('Pinned Lace affinity feature check has changed')
s = s.replace(old, '''include(CMakePushCheckState)
cmake_push_check_state(RESET)
set(CMAKE_REQUIRED_DEFINITIONS -D_GNU_SOURCE)
check_symbol_exists(sched_getaffinity "sched.h" PNDD_HAVE_SCHED_GETAFFINITY)
set(HAVE_SCHED_GETAFFINITY "${PNDD_HAVE_SCHED_GETAFFINITY}")
cmake_pop_check_state()''')
p.write_text(s)

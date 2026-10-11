#!/usr/bin/env python3
"""Fresh-process, sequential, interleaved benchmark. Never overwrites a run.

Build Release first and run Maven package. No timed jobs run concurrently.
Each configuration uses the same RSS ceiling; native arena and Java heap budgets
are recorded separately. OOM, timeout and incorrect counts remain in samples.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import random
import re
import shutil
import signal
import statistics
import subprocess
import tarfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
COUNTS = {1: 1, 2: 0, 3: 0, 4: 2, 5: 10, 6: 4, 7: 40, 8: 92,
          9: 352, 10: 724, 11: 2680, 12: 14200, 13: 73712}

def capture(cmd):
    return subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT).stdout.strip()

def children(pid):
    try:
        child_pids = Path(f'/proc/{pid}/task/{pid}/children').read_text().split()
    except (OSError, ProcessLookupError):
        child_pids = []
    return [int(p) for p in child_pids]

def rss_tree(pid):
    total = 0
    try:
        for line in Path(f'/proc/{pid}/status').read_text().splitlines():
            if line.startswith('VmRSS:'):
                total = int(line.split()[1]) * 1024
                break
    except OSError:
        pass
    return total + sum(rss_tree(p) for p in children(pid))

def native(n, mode, w, compact=0, tree=0, nd=1, lab=0, threshold=4, chunk=2, cache=262144):
    return dict(engine='c', n=n, backend=mode, workers=w, compact=compact, tree=tree,
                ndd=nd, labels=lab, threshold=threshold, chunk=chunk, cache=cache)

def configurations(suite):
    result = []
    if suite in ('smoke', 'all'):
        result += [native(7, b, w) for b in ('BDD', 'BCDD', 'ZDD', 'BITSET', 'MIXED') for w in (1, 2, 4, 6)]
        result += [dict(engine='java', n=7, backend=b, workers=1, compact=0, tree=0) for b in ('BDD', 'BCDD', 'ZDD', 'BITSET')]
    if suite in ('scaling', 'final', 'all'):
        result += [native(n, b, w) for n in (12, 13) for b in ('BDD', 'BCDD', 'ZDD') for w in (1, 2, 4, 6)]
        result += [dict(engine='java', n=n, backend=b, workers=1, compact=0, tree=0) for n in (12, 13) for b in ('BDD', 'BCDD', 'ZDD')]
    if suite in ('compact', 'all'):
        result += [native(n, b, w, compact=1) for n in (10, 12, 13) for b in ('BDD', 'BCDD', 'ZDD', 'BITSET', 'MIXED') for w in (1, 6)]
        result += [dict(engine='java', n=n, backend=b, workers=1, compact=1, tree=0) for n in (10, 12, 13) for b in ('BDD', 'BCDD', 'ZDD', 'BITSET')]
    if suite in ('ablation', 'all'):
        result += [native(12, b, 6, nd=nd, lab=lab) for b in ('BDD', 'BCDD', 'ZDD') for nd, lab in ((0, 0), (0, 1), (1, 0), (1, 1))]
        result += [native(10, b, 6, tree=tree) for b in ('BDD', 'BCDD', 'ZDD') for tree in (0, 1)]
    if suite in ('jni', 'all'):
        result += [dict(engine='jni', n=n, backend=b, workers=w, batch=batch, compact=0, tree=tree)
                   for n in (7, 12) for b in ('BDD', 'BCDD', 'ZDD') for w in (1, 6) for batch, tree in ((0, 0), (1, 0), (1, 1))]
    if suite in ('mid', 'all'):
        result += [native(n, b, w) for n in (10, 11) for b in ('BDD', 'BCDD', 'ZDD') for w in (1, 2, 4, 6)]
        result += [dict(engine='java', n=n, backend=b, workers=1, compact=0, tree=0) for n in (10, 11) for b in ('BDD', 'BCDD', 'ZDD')]
    if suite == 'mtp':
        result += [dict(engine='mtp', n=n, backend='BCDD', workers=w, compact=0, tree=0) for n in (12, 13) for w in (1, 2, 4, 6)]
    if suite == 'mtp-pair':
        result += [native(12, 'BCDD', 6),
                   dict(engine='mtp', n=12, backend='BCDD', workers=6, compact=0, tree=0)]
    if suite == 'cache-large':
        result += [native(13, 'BCDD', 6, cache=c) for c in (65536, 262144)]
    if suite == 'tuning':
        result += [native(12, 'BCDD', 6, threshold=t, chunk=k, cache=c)
                   for t, k in ((4, 2), (8, 4), (16, 8)) for c in (65536, 262144)]
    if suite == 'compact-middle':
        result += [native(n, b, w, compact=1) for n in (10, 12, 13)
                   for b in ('BDD', 'BCDD', 'ZDD', 'BITSET', 'MIXED') for w in (2, 4)]
    if suite == 'jni-large':
        result += [dict(engine='jni', n=13, backend=b, workers=w, batch=batch, compact=0, tree=0)
                   for b in ('BDD', 'BCDD', 'ZDD') for w in (1, 6) for batch in (0, 1)]
    if suite == 'api-comparison':
        result += [dict(engine='jni', n=n, backend=b, workers=6, batch=1, compact=0, tree=0)
                   for n in (12, 13) for b in ('BDD', 'BCDD', 'ZDD')]
        result += [dict(engine='java', n=n, backend=b, workers=1, compact=0, tree=0)
                   for n in (12, 13) for b in ('BDD', 'BCDD', 'ZDD')]
    if suite == 'final':
        # Same interleaved cohort for C scaling and the public Java API comparison.
        result += [dict(engine='jni', n=n, backend=b, workers=6, batch=1, compact=0, tree=0)
                   for n in (12, 13) for b in ('BDD', 'BCDD', 'ZDD')]
    if suite == 'jni-final':
        result += [dict(engine='jni', n=12, backend=b, workers=w, batch=batch, compact=0, tree=0)
                   for b in ('BDD', 'BCDD', 'ZDD') for w in (1, 6) for batch in (0, 1)]
    return result

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--suite', choices=('smoke', 'scaling', 'final', 'compact', 'ablation', 'jni', 'tuning', 'cache-large', 'mtp', 'mtp-pair', 'mid', 'compact-middle', 'jni-large', 'jni-final', 'api-comparison', 'all'), default='smoke')
    p.add_argument('--mtp-root', type=Path, default=Path('/home/cheney/mtpndd'))
    p.add_argument('--repeats', type=int, default=5)
    p.add_argument('--rss-mib', type=int, default=4096)
    p.add_argument('--engine-mib', type=int, default=3072)
    p.add_argument('--timeout', type=float, default=300)
    p.add_argument('--tree-timeout', type=float, help='Optional separate latency budget for balanced-tree experiments; recorded per sample')
    p.add_argument('--out', type=Path)
    p.add_argument('--resume', action='store_true', help='Resume completed CSV rows with identical binaries and parameters')
    p.add_argument('--build', type=Path, default=ROOT / 'native/build-release')
    p.add_argument('--java', default=os.environ.get('JAVA', shutil.which('java') or '/home/cheney/.cache/ndd-bench-tools/sysroot/usr/lib/jvm/java-17-openjdk-amd64/bin/java'))
    p.add_argument('--cores', default='0,2,4,6,8,10', help='Ordered distinct-core CPU IDs; inspect guest topology before use')
    args = p.parse_args()
    if args.repeats < 1 or args.engine_mib < 4 or args.engine_mib >= args.rss_mib:
        p.error('Invalid repetition count or memory budget')
    if args.timeout <= 0 or (args.tree_timeout is not None and args.tree_timeout <= 0):
        p.error('Timeouts must be positive')
    cores = args.cores.split(',')
    configs = configurations(args.suite)
    if len(cores) < max(c['workers'] for c in configs):
        p.error('Not enough distinct-core CPU IDs')
    out = args.out or ROOT / 'results' / ('parallel_ndd_' + time.strftime('%Y%m%d_%H%M%S') + '_' + args.suite)
    out.mkdir(parents=True, exist_ok=args.resume)
    (out / 'logs').mkdir(exist_ok=args.resume)
    classes = out / 'classes'; classes.mkdir(exist_ok=args.resume)
    jar = ROOT / 'target/ndd-1.0.1-jar-with-dependencies.jar'
    if any(c['engine'] == 'java' for c in configs):
        subprocess.run([str(Path(args.java).with_name('javac')), '-cp', str(jar), '-d', str(classes),
                        str(ROOT / 'native/bench/LegacyNQueens.java'), str(ROOT / 'native/bench/LegacyCompactNQueens.java')], check=True)
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()
    sources = [f for f in (ROOT / 'native').rglob('*') if f.is_file()
               and not any(p.startswith('build') or p == '__pycache__' for p in f.relative_to(ROOT / 'native').parts)]
    sources += list((ROOT / 'src/main/java').rglob('*.java')) + [ROOT / 'pom.xml']
    with tarfile.open(out / ('resume_source_snapshot.tar.gz' if args.resume else 'source_snapshot.tar.gz'), 'w:gz') as archive:
        for f in sorted(sources): archive.add(f, arcname=str(f.relative_to(ROOT)))
    metadata = dict(timer_version=2, suite=args.suite, repeats=args.repeats, rss_mib=args.rss_mib, engine_mib=args.engine_mib,
                    java_heap_mib=args.engine_mib, jni_heap_mib=512, timeout=args.timeout, tree_timeout=args.tree_timeout,
                    ndd_commit=capture(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']),
                    worktree_diff=capture(['git', '-C', str(ROOT), 'diff']),
                    source_sha256={str(f.relative_to(ROOT)): sha(f) for f in sources},
                    native_sha256=sha(args.build / 'pndd_nqueens'), jni_sha256=sha(args.build / 'libpnddjni.so'), java_jar_sha256=sha(jar), java=capture([args.java, '-version']),
                    topology=capture(['lscpu']), compiler=capture(['gcc', '--version']), cores=cores,
                    cmake_cache=(args.build / 'CMakeCache.txt').read_text(),
                    methodology='Fresh processes, no warmup, paired shuffled rounds, exclusive sequential samples; outer monotonic wall from process spawn to dedicated wait-thread completion, independent of RSS polling; GNU time/realtime wall recorded separately, /usr/bin/time RSS; 50ms process-tree RSS ceiling; cold JIT included; init includes field generation. Native combined cache and legacy per-operation/backend caches are not identical budgets.')
    if args.suite in ('mtp', 'mtp-pair'):
        metadata['mtp_commit'] = capture(['git', '-C', str(args.mtp_root), 'rev-parse', 'HEAD'])
        metadata['mtp_binary_sha256'] = sha(args.mtp_root / 'build/mtpndd/mtpndd_nqueens_benchmark')
        metadata['mtp_note'] = 'Original fixed-capacity driver; same RSS ceiling but different table/cache allocation and init timing. Ceiling failures are retained.'
    if args.resume:
        previous = json.loads((out / 'metadata.json').read_text())
        for key in ('timer_version', 'suite', 'repeats', 'rss_mib', 'engine_mib', 'timeout', 'tree_timeout', 'native_sha256', 'jni_sha256', 'java_jar_sha256', 'cores', 'topology'):
            if previous.get(key) != metadata.get(key): raise SystemExit(f'Resume mismatch: {key}')
        (out / 'resume_metadata.json').write_text(json.dumps(metadata, indent=2))
    else:
        (out / 'metadata.json').write_text(json.dumps(metadata, indent=2))
    fields = ['round', 'engine', 'n', 'backend', 'workers', 'compact', 'tree', 'batch', 'ndd', 'labels', 'threshold', 'chunk', 'cache', 'timeout_s', 'status', 'solutions', 'init_s', 'build_s', 'reduce_s', 'count_s', 'compute_s', 'cleanup_s', 'wall_s', 'resource_wall_s', 'realtime_wall_s', 'wall_clock_discrepancy_s', 'max_rss_kb', 'rss_peak_sampled_kb', 'nodes_live', 'nodes_created', 'cache_hits', 'cache_misses', 'merge_ns', 'tasks', 'stolen_tasks', 'external_runs', 'gc', 'label_gc', 'native_bytes', 'peak_native_bytes', 'log']
    rows = []
    if args.resume:
        with (out / 'samples.csv').open() as stream:
            if next(csv.reader(stream)) != fields: raise SystemExit('Resume mismatch: CSV schema')
        with (out / 'samples.csv').open() as stream:
            for row in csv.DictReader(stream):
                for key, value in row.copy().items():
                    if not value: row.pop(key); continue
                    if key not in ('engine', 'backend', 'status', 'log'):
                        if value in ('True', 'False'): row[key] = value == 'True'; continue
                        row[key] = float(value) if any(c in value for c in '.eE') else int(value)
                rows.append(row)
    identity_keys = ['engine', 'n', 'backend', 'workers', 'compact', 'tree', 'batch', 'ndd', 'labels', 'threshold', 'chunk', 'cache']
    completed = {(row['round'], tuple(row.get(k) for k in identity_keys)) for row in rows}
    process = None
    def stop_run(signum, frame):
        # Timed children have their own session; stopping only this scheduler
        # would otherwise leave a CPU-consuming orphan and corrupt later runs.
        if process is not None and process.poll() is None:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait()
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, stop_run)
    signal.signal(signal.SIGINT, stop_run)
    with (out / 'samples.csv').open('a' if args.resume else 'w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction='ignore');
        if not args.resume: writer.writeheader()
        for r in range(1, args.repeats + 1):
            jobs = configs[:]; random.Random(20261011 + r).shuffle(jobs)
            for index, config in enumerate(jobs):
                if (r, tuple(config.get(k) for k in identity_keys)) in completed: continue
                c = config.copy(); w = c['workers']; n = c['n']; mode = c['backend']
                effective_timeout = args.tree_timeout if c.get('tree') and args.tree_timeout is not None else args.timeout
                if c['engine'] == 'c':
                    cmd = [str(args.build / 'pndd_nqueens'), str(n), mode, str(w), str(c['compact']), str(c['tree']),
                           str(args.engine_mib), str(c['ndd']), str(c['labels']), '1', str(c['threshold']), str(c['chunk']), str(c['cache'])]
                elif c['engine'] == 'java':
                    flag = {'BDD': [], 'BCDD': ['--bcdd'], 'ZDD': ['--zdd'], 'BITSET': ['--bitset']}[mode]
                    cmd = [args.java, f'-Xmx{args.engine_mib}m', '-XX:ActiveProcessorCount=1', '-cp', f'{classes}:{jar}', 'org.ants.jndd.parallel.LegacyNQueens'] + flag + [str(n)]
                    if c['compact']:
                        cmd = [args.java, f'-Xmx{args.engine_mib}m', '-XX:ActiveProcessorCount=1', '-cp', f'{classes}:{jar}', 'org.ants.jndd.parallel.LegacyCompactNQueens', str(n), mode]
                elif c['engine'] == 'mtp':
                    cmd = [str(args.mtp_root / 'build/mtpndd/mtpndd_nqueens_benchmark'), str(n), str(w)]
                else:
                    cmd = [args.java, '-Xmx512m', f'-XX:ActiveProcessorCount={w}', f'-Dpndd.library={args.build / "libpnddjni.so"}', '-cp', str(jar), 'org.ants.jndd.parallel.ParallelNQueens', str(n), mode, str(w), str(bool(c['batch'])).lower(), str(bool(c['tree'])).lower(), str(args.engine_mib)]
                cmd = ['/usr/bin/time', '-f', 'RESOURCE wall_s=%e max_rss_kb=%M', 'taskset', '-c', ','.join(cores[:w])] + cmd
                logfile = out / 'logs' / f'r{r}_{index}_{c["engine"]}_{mode}_n{n}_w{w}.log'
                forced = None; sampled_peak = 0; finished = {}
                with logfile.open('w+') as log:
                    log.write(json.dumps(cmd) + '\n'); log.flush()
                    start = time.monotonic(); realtime_start = time.time()
                    process = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                    def record_finish():
                        process.wait()
                        finished['monotonic'] = time.monotonic()
                        finished['realtime'] = time.time()
                    waiter = threading.Thread(target=record_finish, daemon=True); waiter.start()
                    while process.poll() is None:
                        observed_rss = rss_tree(process.pid)
                        sampled_peak = max(sampled_peak, observed_rss)
                        if time.monotonic() - start > effective_timeout: forced = 'timeout'
                        elif observed_rss > args.rss_mib * 1024 * 1024: forced = 'rss_limit'
                        if forced:
                            try: os.killpg(process.pid, signal.SIGKILL)
                            except ProcessLookupError: pass
                            process.wait(); break
                        time.sleep(0.05)
                    waiter.join()
                text = logfile.read_text(); row = dict(c, round=r, timeout_s=effective_timeout, status=forced or ('ok' if process.returncode == 0 else 'error'), wall_s=finished['monotonic'] - start, realtime_wall_s=finished['realtime'] - realtime_start, rss_peak_sampled_kb=(sampled_peak + 1023) // 1024, log=str(logfile.relative_to(out)))
                for line in text.splitlines():
                    if line.startswith('{'):
                        try: row.update(json.loads(line))
                        except json.JSONDecodeError: pass
                if c['engine'] == 'mtp':
                    match = re.search(r'^\s*([0-9.]+)\s+(\d+)\s*$', text, re.M)
                    if match: row.update(compute_s=float(match[1]), solutions=int(match[2]))
                resource = re.search(r'RESOURCE wall_s=([\d.]+) max_rss_kb=(\d+)', text)
                if resource: row.update(resource_wall_s=float(resource[1]), max_rss_kb=int(resource[2]))
                row['wall_clock_discrepancy_s'] = row['wall_s'] - row['realtime_wall_s']
                if row['status'] == 'ok' and row.get('solutions') != COUNTS[n]: row['status'] = 'incorrect_count'
                if row['status'] == 'error' and ('OutOfMemoryError' in text or 'memory budget' in text): row['status'] = 'allocation_limit'
                rows.append(row); writer.writerow(row); stream.flush()
                print(f'{r}/{args.repeats} {c["engine"]} {mode} N={n} w={w} {row["status"]} {row.get("compute_s", "-")}', flush=True)
    keys = ['engine', 'n', 'backend', 'workers', 'compact', 'tree', 'batch', 'ndd', 'labels', 'threshold', 'chunk', 'cache']
    groups = {}
    for row in rows: groups.setdefault(tuple(row.get(k) for k in keys), []).append(row)
    summary = []
    for key, group in groups.items():
        entry = dict(zip(keys, key)); successful = [x for x in group if x['status'] == 'ok']
        entry['successes'] = len(successful); entry['failures'] = [x['status'] for x in group if x['status'] != 'ok']
        for metric in ('compute_s', 'wall_s', 'max_rss_kb', 'init_s', 'build_s', 'reduce_s', 'count_s', 'cleanup_s'):
            values = [x[metric] for x in successful if metric in x]
            if values: entry[metric] = dict(median=statistics.median(values), minimum=min(values), maximum=max(values))
        summary.append(entry)
    (out / 'summary.json').write_text(json.dumps(summary, indent=2))
    # Keep raw commands/stdout/stderr portable even when loose logs are ignored.
    with tarfile.open(out / 'raw_logs.tar.gz', 'w:gz') as archive:
        for logfile in sorted((out / 'logs').glob('*.log')):
            archive.add(logfile, arcname=str(logfile.relative_to(out)))
    report = ['# Parallel NDD benchmark', '', f'Suite `{args.suite}`, {args.repeats} fresh-process repetitions per configuration. RSS ceiling {args.rss_mib} MiB; native budget / legacy heap {args.engine_mib} MiB. Timeout {args.timeout}s; balanced-tree timeout {args.tree_timeout if args.tree_timeout is not None else args.timeout}s.', '', '| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |', '|---|---|---:|---:|---|---|---|---:|---|---:|']
    for e in sorted(summary, key=lambda x: (x['n'], x['backend'], x['engine'], x['workers'], x.get('compact') or 0, x.get('tree') or 0)):
        t = e.get('compute_s'); ts = '-' if not t else f'{t["median"]:.4f} [{t["minimum"]:.4f}–{t["maximum"]:.4f}]'
        rss = '-' if 'max_rss_kb' not in e else f'{e["max_rss_kb"]["median"] / 1024:.1f}'
        report.append(f'| {e["engine"]} | {e["backend"]} | {e["n"]} | {e["workers"]} | {e["compact"]}/{e["tree"]}/{e.get("batch")} | {e.get("ndd")}/{e.get("labels")} | {e.get("threshold")}/{e.get("chunk")}/{e.get("cache")} | {e["successes"]}/{args.repeats} | {ts} | {rss} |')
    report += ['', 'All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), [raw logs](raw_logs.tar.gz) (extracts to logs/).', '', 'Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.']
    (out / 'README.md').write_text('\n'.join(report) + '\n')
    print(out, flush=True)

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Regenerate scaling ratios from the completed fresh-process CSV cohort."""
import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path

root = Path(__file__).resolve().parent
metadata = json.loads((root / 'metadata.json').read_text())
rows = list(csv.DictReader((root / 'samples.csv').open()))
groups = defaultdict(list)
for row in rows:
    groups[int(row['n']), row['backend'], int(row['workers'])].append(row)
expected = {(n, b, w) for n in (12, 13) for b in ('BDD', 'BCDD', 'ZDD')
            for w in metadata['worker_grid']}
assert set(groups) == expected
assert all(len(g) == metadata['repeats'] for g in groups.values())
assert all(r['status'] == 'ok' and int(r['solutions']) == {12: 14200, 13: 73712}[int(r['n'])]
           for r in rows), 'Incomplete/failed samples must be examined before reporting speedups'

medians = {}
for key, group in groups.items():
    medians[key] = {metric: statistics.median(float(r[metric]) for r in group)
                    for metric in ('compute_s', 'wall_s', 'init_s', 'build_s', 'reduce_s',
                                   'count_s', 'cleanup_s', 'max_rss_kb', 'tasks', 'stolen_tasks')}
output = []
for (n, backend, workers), metrics in sorted(medians.items()):
    base = medians[n, backend, 1]
    group = groups[n, backend, workers]
    base_rounds = {r['round']: float(r['compute_s']) for r in groups[n, backend, 1]}
    paired = [base_rounds[r['round']] / float(r['compute_s']) for r in group]
    speedup = base['compute_s'] / metrics['compute_s']
    output.append(dict(n=n, backend=backend, workers=workers, **metrics,
                       compute_min_s=min(float(r['compute_s']) for r in group),
                       compute_max_s=max(float(r['compute_s']) for r in group),
                       compute_speedup=speedup, wall_speedup=base['wall_s'] / metrics['wall_s'],
                       worker_efficiency=speedup / workers,
                       paired_speedup_median=statistics.median(paired),
                       paired_speedup_min=min(paired), paired_speedup_max=max(paired)))
with (root / 'scaling.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(output[0]))
    writer.writeheader(); writer.writerows(output)
best_rows = {metric: [min((r for r in output if r['n'] == n and r['backend'] == b),
                         key=lambda r: r[metric + '_s'])
                      for n in (12, 13) for b in ('BDD', 'BCDD', 'ZDD')]
             for metric in ('compute', 'wall')}
(root / 'best.json').write_text(json.dumps(best_rows, indent=2) + '\n')

report = ['# N=12/13: wider worker scaling', '',
          'Native C core, original field encoding, fixed left-fold. Each point is the median '
          'of five shuffled fresh-process runs; speedup = median(T_native(1)) / median(T_native(p)). '
          'The baseline uses the same binary/backend/configuration with one Lace worker. '
          'Wall speedup includes initialization and cleanup. These are self-scaling ratios, '
          'not ratios against the legacy Java implementation.', '',
          '| N | Backend | 1 worker s | Best workers | Best compute s [min–max] | Compute speedup | Wall speedup at same workers | Peak RSS median MiB |',
          '|---:|---|---:|---:|---:|---:|---:|---:|']
for n in (12, 13):
    for b in ('BDD', 'BCDD', 'ZDD'):
        best = min((r for r in output if r['n'] == n and r['backend'] == b), key=lambda r: r['compute_s'])
        base = medians[n, b, 1]['compute_s']
        report.append(f"| {n} | {b} | {base:.4f} | {best['workers']} | {best['compute_s']:.4f} "
                      f"[{best['compute_min_s']:.4f}–{best['compute_max_s']:.4f}] | "
                      f"{best['compute_speedup']:.3f}× | {best['wall_speedup']:.3f}× | {best['max_rss_kb']/1024:.1f} |")
for n in (12, 13):
    report += ['', f'## N={n}: all tested worker counts', '',
               '| Workers | BDD s / speedup | BCDD s / speedup | ZDD s / speedup |',
               '|---:|---:|---:|---:|']
    for w in metadata['worker_grid']:
        cells = []
        for b in ('BDD', 'BCDD', 'ZDD'):
            row = next(r for r in output if (r['n'], r['backend'], r['workers']) == (n, b, w))
            cells.append(f"{row['compute_s']:.4f} / {row['compute_speedup']:.3f}×")
        report.append(f'| {w} | ' + ' | '.join(cells) + ' |')
report += ['', '## Independently selected end-to-end peaks', '',
           '| N | Backend | Best workers by wall time | Wall median s | Wall speedup |',
           '|---:|---|---:|---:|---:|']
for best in best_rows['wall']:
    report.append(f"| {best['n']} | {best['backend']} | {best['workers']} | {best['wall_s']:.4f} | {best['wall_speedup']:.3f}× |")
report += ['', '## Reproduction and interpretation', '',
           '- 28 guest logical CPUs / 14 guest cores. Affinity order uses one logical CPU per core '
           'first, then SMT siblings; workers can migrate within the assigned mask. Guest topology '
           'does not guarantee physical host core placement or describe host P/E cores.',
           '- Default task threshold 4, chunk 2, spawn depth 4, combined cache 262144 entries; '
           'NDD/batch spawning on, label spawning off. Graph budget 3072 MiB, process RSS '
           'ceiling 4096 MiB, timeout 120 s. All configurations share these settings.',
           '- Best means the smallest median among this fixed worker grid. Small differences '
           'between nearby configurations can be within run-to-run variability. More workers '
           'need not lower execution time. Hyperthreads do not supply independent full cores.',
           '- BITSET is excluded because original N=12/13 field widths exceed its seven-bit limit. '
           'Compact encoding is a separate experiment and is not mixed into these ratios.', '',
           '```sh',
           'python3 native/scripts/benchmark.py --suite wide-scaling --repeats 5 --timeout 120 \\',
           '  --rss-mib 4096 --engine-mib 3072 \\',
           '  --cores 0,2,4,6,8,10,12,14,16,18,20,22,24,26,1,3,5,7,9,11,13,15,17,19,21,23,25,27 \\',
           '  --out results/parallel_ndd_wide_NEW',
           'cp results/parallel_ndd_20261011_wide_scaling/analyze.py results/parallel_ndd_wide_NEW/',
           'python3 results/parallel_ndd_wide_NEW/analyze.py',
           '```', '',
           'Evidence: [raw samples](samples.csv), [derived scaling](scaling.csv), '
           '[selected peaks](best.json), [configuration/provenance](metadata.json), [raw logs](raw_logs.tar.gz), '
           '[source snapshot](source_snapshot.tar.gz). Analysis: [analyze.py](analyze.py).']
if (root / 'scaling.svg').exists():
    report += ['', '## Scaling curve', '', '![Measured scaling](scaling.svg)', '',
               '[PNG](scaling.png), [plot source](plot.py). Rendered using matplotlib 3.9.4.']
if (root / 'validation_28_workers_crossproduct.log').exists():
    report += ['', '## Follow-up correctness and GC validation', '',
               'All 300 timed samples returned the known solution counts. The follow-up '
               '28-worker test exposed a scheduling-dependent assertion in the old pressure '
               'fixture, which required the first OR batch to trigger a GC. A diagnostic '
               'run retained all semantic checks and showed ZDD construction had already grown '
               'the table to 32768 with 28 workers, whereas it was still 16384 with 6 workers. '
               'The former batch fit without another GC. Worker-local 512-bucket allocation '
               'regions affect when the table grows.', '',
               'The revised fixture uses a 256-by-128 Cartesian product of half-word cubes '
               'inside one AND batch, verifies 32768 exact singleton results, and forces table '
               'growth during active tasks. Its separate test budget is 64 MiB. Release '
               '1/2/4/6/28-worker tests and ASan/UBSan with 28 workers passed. Only the fixture '
               'changed after timing; the measured core, driver and JNI hashes remained unchanged.', '',
               'Evidence: [original assertion failure](validation_28_workers.log), '
               '[diagnostic log](pressure_diagnostic.log), [diagnostic source](pressure_diagnostic.c), '
               '[revised test source](validation_test_pndd.c), '
               '[Release 1/2/4/6](validation_release_ctest.log), '
               '[Release 28](validation_28_workers_crossproduct.log), '
               '[ASan/UBSan 28](validation_asan_28_workers.log).']
(root / 'REPORT.md').write_text('\n'.join(report) + '\n')
print('\n'.join(report[:13]))

#!/usr/bin/env python3
"""Audit all core-scaling experiments and produce the final same-workload table."""
import argparse
import csv
import json
import statistics
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('root', type=Path)
a = p.parse_args(); root = a.root.resolve()
phases = {'cache08': 8, 'cache12': 4, 'grain08': 8, 'label08': 4, 'label12': 4,
          'affinity08': 8, 'affinity12': 4, 'confirm': 16}
rows, checks, summaries, class_sets = [], [], [], []
for phase, expected in phases.items():
    directory = root / phase
    if not (directory / 'runs.jsonl').exists():
        checks.append(dict(phase=phase, complete=False)); continue
    selected = [json.loads(line) for line in (directory / 'runs.jsonl').read_text().splitlines()]
    rows.extend(dict(phase=phase, **r) for r in selected)
    check = json.loads((directory / 'checks.json').read_text()) if (directory / 'checks.json').exists() else dict(valid=False)
    checks.append(dict(phase=phase, complete=len(selected) == expected, valid=check['valid']))
    metadata = json.loads((directory / 'metadata.json').read_text())
    class_sets.append((metadata['jar_sha256'], metadata['class_hashes']))
    if (directory / 'summary.json').exists():
        summaries.extend(dict(phase=phase, **r) for r in json.loads((directory / 'summary.json').read_text()))
same_workload = bool(class_sets) and all(s == class_sets[0] for s in class_sets)
valid = same_workload and all(v for c in checks for k, v in c.items() if k != 'phase')
(root / 'checks-all.json').write_text(json.dumps(dict(valid=valid, timed_runs=len(rows),
    same_java_workload=same_workload, phases=checks, diagnostic_runs_excluded=4), indent=2) + '\n')
(root / 'summary-all.json').write_text(json.dumps(summaries, indent=2) + '\n')
keys = sorted(set().union(*(r.keys() for r in rows)) - {'command'})
with (root / 'runs-all.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=keys); writer.writeheader()
    writer.writerows({k: v for k, v in r.items() if k != 'command'} for r in rows)
final = [r for r in summaries if r['phase'] == 'confirm']
lines = ['| 输入 | 旧 1 worker s | 旧 6 workers s | 旧自身加速 | 新 1 worker s | 新 6 workers s | 新自身加速 | 六线程改善 |',
         '|---|---:|---:|---:|---:|---:|---:|---:|']
for size in (8, 12):
    lookup = {(r['variant'], r['workers']): r for r in final if r['size'] == size}
    if len(lookup) != 4:
        continue
    old1, old6, new1, new6 = [lookup[key]['total'] for key in
        [('baseline', 1), ('baseline', 6), ('rootcache', 1), ('rootcache', 6)]]
    lines.append(f'| {size:02d}/MF3 | {old1:.3f} | {old6:.3f} | {old1/old6:.3f}× | {new1:.3f} | {new6:.3f} | {new1/new6:.3f}× | {old6/new6:.3f}× |')
(root / 'tables.md').write_text('\n'.join(lines) + '\n')
print(json.dumps(dict(valid=valid, timed_runs=len(rows), same_java_workload=same_workload), indent=2))
print('\n'.join(lines))
if not valid:
    raise SystemExit('Missing or mismatching experiment; inspect checks-all.json.')

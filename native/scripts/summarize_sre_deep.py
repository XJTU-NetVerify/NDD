#!/usr/bin/env python3
"""Audit the frozen-control SRE matrix and publish means without hiding failures."""
import argparse
import collections
import csv
import hashlib
import json
import statistics
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('root', type=Path)
p.add_argument('--golden', type=Path, default=Path(__file__).resolve().parents[2] /
               'results/sre_parallel_20261011_final/runs.jsonl')
a = p.parse_args()
root = a.root.resolve()
rows = []
metadata = []
for size in (4, 8, 12):
    directory = root / f'final{size:02d}'
    metadata.append(json.loads((directory / 'metadata.json').read_text()))
    rows.extend(json.loads(line) for line in (directory / 'runs.jsonl').read_text().splitlines())
golden_file = a.golden.resolve()
golden = [json.loads(line) for line in golden_file.read_text().splitlines()]
fields = ['bgp_routes', 'ndd_field_layout', 'link_variable_order_hash', 'link_polarity',
          'semantic_condition_rows', 'semantic_condition_sha256',
          'rib_rows', 'rib_sha256', 'reachability_csv_rows', 'reachability_csv_sha256']
historic_fields = fields[:6]
groups = collections.defaultdict(list)
for row in rows:
    groups[row['size'], row['mf'], row['engine']].append(row)
checks = []
summary = []
for size in (4, 8, 12):
    for mf in (1, 2, 3):
        case = [r for r in rows if (r['size'], r['mf']) == (size, mf)]
        reference = next(r for r in golden if (r['size'], r['mf']) == (size, mf))
        check = dict(size=size, mf=mf, eight_runs=len(case) == 8,
                     all_ok=all(r['status'] == 'ok' for r in case),
                     same_outputs=all(len({str(r.get(k)) for r in case}) == 1 for k in fields),
                     original_outputs=all(str(r.get(k)) == str(reference.get(k))
                                          for r in case for k in historic_fields))
        checks.append(check)
        for engine in ('java', 'native1', 'native2', 'native6'):
            selected = groups[size, mf, engine]
            out = dict(size=size, mf=mf, engine=engine, samples=len(selected),
                       successful=sum(r['status'] == 'ok' for r in selected),
                       repeats=sorted(r['repeat'] for r in selected))
            numeric = set.intersection(*(set(k for k, v in r.items() if type(v) in (int, float))
                                         for r in selected)) if selected else set()
            for key in sorted(numeric - {'size', 'mf', 'repeat', 'exitcode'}):
                values = [r[key] for r in selected]
                out[key] = statistics.mean(values)
                if key in ('total', 'wall_seconds', 'peak_rss_mib'):
                    out[key + '_range'] = [min(values), max(values)]
            summary.append(out)
versions = {key: len({m[key] for m in metadata}) == 1 for key in
            ('native_library_sha256', 'ndd_jar_sha256', 'java_control_jar_sha256',
             'java_control_adapter_sha256')}
valid = len(rows) == 72 and all(versions.values()) and all(
    all(v for k, v in c.items() if k not in ('size', 'mf')) for c in checks) and all(
    s['repeats'] == [1, 2] for s in summary)
audit = dict(valid=valid, runs=len(rows), golden_sha256=hashlib.sha256(golden_file.read_bytes()).hexdigest(),
             binary_versions_match=versions, cases=checks)
(root / 'final-checks.json').write_text(json.dumps(audit, indent=2) + '\n')
(root / 'final-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
keys = sorted(set().union(*(r.keys() for r in rows)) - {'command'})
with (root / 'final-runs.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=keys)
    writer.writeheader()
    writer.writerows({k: v for k, v in r.items() if k != 'command'} for r in rows)
lookup = {(s['size'], s['mf'], s['engine']): s for s in summary}
lines = ['| fattree / MF | Java s | native1 s | native2 s | native6 s | Java/native2 | native1/native2 |',
         '|---|---:|---:|---:|---:|---:|---:|']
memory = ['| fattree / MF | Java RSS MiB | native1 RSS MiB | native2 RSS MiB | native6 RSS MiB |',
          '|---|---:|---:|---:|---:|']
for size in (4, 8, 12):
    for mf in (1, 2, 3):
        values = [lookup[size, mf, engine] for engine in ('java', 'native1', 'native2', 'native6')]
        times = [v.get('total', float('nan')) for v in values]
        lines.append(f'| {size:02d} / {mf} | ' + ' | '.join(f'{x:.3f}' for x in times) +
                     f' | {times[0]/times[2]:.3f}× | {times[1]/times[2]:.3f}× |')
        memory.append(f'| {size:02d} / {mf} | ' + ' | '.join(f'{v.get("peak_rss_mib",float("nan")):.1f}' for v in values) + ' |')
(root / 'final-tables.md').write_text('\n'.join(lines) + '\n\n' + '\n'.join(memory) + '\n')
print(json.dumps(audit, indent=2))
print('\n'.join(lines))
if not valid:
    raise SystemExit('Incomplete or mismatching matrix; inspect final-checks.json before reporting.')

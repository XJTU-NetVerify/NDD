#!/usr/bin/env python3
"""Interleave native builds/workers while freezing the Java workload and budget."""
import argparse
import csv
import hashlib
import json
import random
import statistics
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sre', type=Path, default=Path('/home/cheney/SRE'))
p.add_argument('--classes', type=Path, required=True)
p.add_argument('--jar', type=Path, required=True)
p.add_argument('--java', required=True)
p.add_argument('--library', action='append', required=True, help='NAME=/absolute/libpnddjni.so')
p.add_argument('--sizes', default='8,12')
p.add_argument('--mf', type=int, default=3)
p.add_argument('--repeats', type=int, default=2)
p.add_argument('--workers', default='1,6')
p.add_argument('--cpus', default='0,2,4,6,8,10')
p.add_argument('--labels', choices=['true', 'false'], default='false')
p.add_argument('--out', type=Path, required=True)
p.add_argument('--golden', type=Path, default=ROOT / 'results/sre_priority_batch_20261012/runs.csv')
a = p.parse_args()
if a.out.exists():
    p.error('Use a fresh output directory')
a.out = a.out.resolve(); a.out.mkdir(parents=True)
libraries = {name: Path(path).resolve() for name, path in (s.split('=', 1) for s in a.library)}
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
meta = dict(libraries={name: dict(path=str(path), sha256=sha(path)) for name, path in libraries.items()},
            jar=str(a.jar.resolve()), jar_sha256=sha(a.jar), classes=str(a.classes.resolve()),
            class_hashes={str(f.relative_to(a.classes)): sha(f) for f in sorted(a.classes.rglob('*.class'))},
            arguments={k: str(v) if isinstance(v, Path) else v for k, v in vars(a).items()})
(a.out / 'metadata.json').write_text(json.dumps(meta, indent=2) + '\n')
with a.golden.open(newline='') as stream:
    golden = list(csv.DictReader(stream))
fields = ['bgp_routes', 'ndd_field_layout', 'link_variable_order_hash', 'link_polarity',
          'semantic_condition_rows', 'semantic_condition_sha256', 'rib_rows', 'rib_sha256',
          'reachability_csv_rows', 'reachability_csv_sha256']
schedule = []
for repeat in range(1, a.repeats + 1):
    cases = [(size, name, workers, repeat) for size in map(int, a.sizes.split(','))
             for name in libraries for workers in map(int, a.workers.split(','))]
    random.Random(20261012 + repeat).shuffle(cases); schedule.extend(cases)
(a.out / 'schedule.json').write_text(json.dumps(schedule, indent=2) + '\n')
rows = []
for size, name, workers, repeat in schedule:
    directory = a.out / f'ft{size:02d}-{name}-{workers}-r{repeat}'
    cmd = ['python3', str(a.sre / 'experiments/parallel-ndd/benchmark.py'),
           '--ndd', str(ROOT), '--java', a.java, '--native-classes', str(a.classes.resolve()),
           '--native-jar', str(a.jar.resolve()), '--library', str(libraries[name]),
           '--output', str(directory), '--sizes', str(size), '--mfs', str(a.mf),
           '--engines', f'native{workers}', '--repeats', '1', '--repeat-start', str(repeat),
           '--cache', str({4: 262144, 8: 4194304, 12: 16777216}[size]),
           '--gc-high-water-mib', '1228', '--priority-lists', 'true', '--priority-list-block', '64',
           '--cpus', a.cpus, '--labels', a.labels]
    subprocess.run(cmd, cwd=a.sre, check=True)
    row = json.loads((directory / 'runs.jsonl').read_text().strip())
    row.update(variant=name, source_directory=directory.name)
    reference = next(r for r in golden if int(r['size']) == size and int(r['mf']) == a.mf)
    row['semantic_ok'] = row['status'] == 'ok' and all(str(row.get(k)) == str(reference.get(k)) for k in fields)
    rows.append(row)
    with (a.out / 'runs.jsonl').open('a') as stream:
        stream.write(json.dumps(row) + '\n')
    if not row['semantic_ok']:
        raise SystemExit('Output mismatch or failed run; inspect raw logs before continuing.')
    if sha(libraries[name]) != meta['libraries'][name]['sha256'] or sha(a.jar) != meta['jar_sha256']:
        raise SystemExit('A timed artifact changed during the experiment.')
summary = []
for size in map(int, a.sizes.split(',')):
    for name in libraries:
        for workers in map(int, a.workers.split(',')):
            selected = [r for r in rows if r['size'] == size and r['variant'] == name and r['ndd_workers'] == workers]
            out = dict(size=size, variant=name, workers=workers, samples=len(selected))
            keys = set.intersection(*(set(k for k, v in r.items() if type(v) in (int, float)) for r in selected))
            for k in keys - {'size', 'mf', 'repeat', 'ndd_workers', 'exitcode'}:
                out[k] = statistics.mean(r[k] for r in selected)
                if k in ('total', 'src_compute', 'wall_seconds', 'peak_rss_mib'):
                    out[k + '_range'] = [min(r[k] for r in selected), max(r[k] for r in selected)]
            summary.append(out)
for out in summary:
    single = next(r for r in summary if r['size'] == out['size'] and r['variant'] == out['variant'] and r['workers'] == 1)
    out['self_scaling'] = single['total'] / out['total']
(a.out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
keys = sorted(set().union(*(r.keys() for r in rows)) - {'command'})
with (a.out / 'runs.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=keys); writer.writeheader()
    writer.writerows({k: v for k, v in r.items() if k != 'command'} for r in rows)
same_classes = {str(f.relative_to(a.classes)): sha(f) for f in sorted(a.classes.rglob('*.class'))} == meta['class_hashes']
valid = same_classes and all(r['semantic_ok'] for r in rows) and all(s['samples'] == a.repeats for s in summary)
(a.out / 'checks.json').write_text(json.dumps(dict(valid=valid, runs=len(rows), same_java_classes=same_classes,
                                                semantic_fields=fields), indent=2) + '\n')
print(json.dumps(summary, indent=2))
if not valid:
    raise SystemExit('Incomplete or changed workload.')

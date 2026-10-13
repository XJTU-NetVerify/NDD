#!/usr/bin/env python3
"""A/B/D SRE: original Java versus frozen native 1/6-worker controls."""
import argparse
import csv
import hashlib
import json
import random
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sre', type=Path, default=Path('/home/cheney/SRE'))
p.add_argument('--artifacts', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--sizes', default='4,8,12')
p.add_argument('--mfs', default='1,2,3')
p.add_argument('--profiles', default='a,b,d')
p.add_argument('--repeats', type=int, default=2)
p.add_argument('--timeout', type=int, default=900)
p.add_argument('--labels', choices=['true', 'false'], default='false')
p.add_argument('--cpus', default='0,2,4,6,8,10')
a = p.parse_args()
a.artifacts = a.artifacts.resolve(); a.output = a.output.resolve()
if a.output.exists():
    p.error('Use a fresh output directory')
a.output.mkdir(parents=True)
schedule = [(profile, size) for profile in a.profiles.split(',') for size in map(int, a.sizes.split(','))]
random.Random(20261013).shuffle(schedule)
(a.output / 'schedule.json').write_text(json.dumps(schedule, indent=2) + '\n')
fields = ['bgp_routes', 'ndd_field_layout', 'link_variable_order_hash', 'link_polarity',
          'semantic_condition_rows', 'semantic_condition_sha256', 'rib_rows', 'rib_sha256',
          'reachability_csv_rows', 'reachability_csv_sha256']
rows = []
for profile, size in schedule:
    directory = a.output / f'{profile}-ft{size:02d}'
    cmd = ['python3', str(a.sre / 'experiments/parallel-ndd/benchmark.py'),
           '--profile', profile, '--sizes', str(size), '--mfs', a.mfs, '--repeats', str(a.repeats),
           '--java-classes', str(a.artifacts / 'java-classes'),
           '--java-jar', str(a.artifacts / 'java-ndd.jar'),
           '--native-classes', str(a.artifacts / 'native-classes'),
           '--native-jar', str(a.artifacts / 'native-ndd.jar'),
           '--library', str(a.artifacts / 'libpnddjni.so'),
           '--cache', str({4: 262144, 8: 4194304, 12: 16777216}[size]),
           '--gc-high-water-mib', '1228', '--timeout', str(a.timeout),
           '--labels', a.labels, '--cpus', a.cpus, '--output', str(directory)]
    print(f'BEGIN profile={profile.upper()} fattree{size:02d}', flush=True)
    process = subprocess.run(cmd, cwd=a.sre)
    partial = [json.loads(s) for s in (directory / 'runs.jsonl').read_text().splitlines()]
    rows.extend(partial)
    with (a.output / 'runs.jsonl').open('w') as stream:
        for row in rows: stream.write(json.dumps(row) + '\n')
    # Resource failures are retained and do not hide the other profiles. A
    # mismatch between successful processes stops the experiment immediately.
    for mf in map(int, a.mfs.split(',')):
        group = [r for r in partial if r['mf'] == mf and r['status'] == 'ok']
        if any(len({str(r.get(k)) for r in group}) > 1 for k in fields):
            raise SystemExit(f'Semantic mismatch in {profile}/{size}/{mf}')
    expected = len(a.mfs.split(',')) * 3 * a.repeats
    if len(partial) != expected:
        raise SystemExit(f'Harness stopped before completing {profile}/{size}: exit {process.returncode}')
keys = sorted(set().union(*(r.keys() for r in rows)) - {'command'})
with (a.output / 'runs.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, keys); writer.writeheader()
    writer.writerows({k: v for k, v in r.items() if k != 'command'} for r in rows)
print(f'Completed {len(rows)} fresh processes; failures: {sum(r["status"] != "ok" for r in rows)}', flush=True)

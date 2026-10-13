#!/usr/bin/env python3
"""Audit the independent-priority-list experiment, including frozen controls."""
import argparse
import collections
import csv
import hashlib
import json
import statistics
from pathlib import Path


def read_rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--golden', type=Path, default=Path(__file__).resolve().parents[2] /
                        'results/sre_deep_optimization_20261012/final-runs.csv')
    args = parser.parse_args()
    root = args.root.resolve()
    with args.golden.open(newline='') as stream:
        golden = list(csv.DictReader(stream))
    fields = ['bgp_routes', 'ndd_field_layout', 'link_variable_order_hash', 'link_polarity',
              'semantic_condition_rows', 'semantic_condition_sha256', 'rib_rows', 'rib_sha256',
              'reachability_csv_rows', 'reachability_csv_sha256']
    phases = {'first08-on': ('java', 'native1', 'native6'),
              'first08-off': ('native1', 'native6'),
              'first08-old': ('native1', 'native6'),
              'large12-on': ('java', 'native1', 'native2', 'native6'),
              'large12-off': ('native1', 'native6'),
              'large12-old': ('native1', 'native6')}
    checks, summary, all_rows = [], [], []
    versions = collections.defaultdict(list)
    for phase, engines in phases.items():
        directory = root / phase
        files = [directory / 'runs.jsonl'] + sorted(root.glob(phase + '-r*/runs.jsonl'))
        for path in files:
            metadata = path.parent / 'metadata.json'
            if metadata.exists():
                versions[phase].append(json.loads(metadata.read_text()))
        rows = [dict(source_directory=str(path.parent.relative_to(root)), **r)
                for path in files if path.exists() for r in read_rows(path)]
        grouped = collections.defaultdict(list)
        for row in rows:
            grouped[row['size'], row['mf'], row['engine']].append(row)
            all_rows.append(dict(phase=phase, **row))
        expected_size = 8 if phase.startswith('first08') else 12
        check = dict(phase=phase, runs=len(rows), complete=len(rows) == 2 * len(engines),
                     expected_case=all(r['size'] == expected_size and r['mf'] == 3 for r in rows),
                     all_ok=all(r['status'] == 'ok' for r in rows),
                     same_outputs=bool(rows) and all(len({str(r.get(k)) for r in rows}) == 1 for k in fields),
                     golden_outputs=bool(rows))
        for row in rows:
            reference = next(r for r in golden if int(r['size']) == row['size'] and int(r['mf']) == row['mf'])
            check['golden_outputs'] &= all(str(row.get(k)) == str(reference.get(k)) for k in fields)
        for engine in engines:
            selected = grouped[expected_size, 3, engine]
            out = dict(phase=phase, size=expected_size, mf=3, engine=engine,
                       samples=len(selected), repeats=sorted(r['repeat'] for r in selected))
            check['complete'] &= out['repeats'] == [1, 2]
            numeric = set.intersection(*(set(k for k, v in r.items() if type(v) in (int, float))
                                         for r in selected)) if selected else set()
            for key in sorted(numeric - {'size', 'mf', 'repeat', 'exitcode'}):
                values = [r[key] for r in selected]
                out[key] = statistics.mean(values)
                if key in ('total', 'wall_seconds', 'peak_rss_mib', 'src_compute'):
                    out[key + '_range'] = [min(values), max(values)]
            summary.append(out)
        checks.append(check)
    controls = []
    for prefix in ('first08', 'large12'):
        on, off, old = [versions[prefix + '-' + kind] for kind in ('on', 'off', 'old')]
        if on and off and old:
            frames = on + off + old
            java_frames = [m for m in frames if 'java' in m['arguments']['engines'].split(',')]
            configurations = [{k: v for k, v in m['configuration'].items() if k != 'priority_lists'} for m in frames]
            controls.append(dict(case=prefix,
                                 same_build_on_off=len({m['native_library_sha256'] for m in on + off}) == 1 and
                                 len({m['ndd_jar_sha256'] for m in on + off}) == 1,
                                 same_capacity_and_flags=all(c == configurations[0] for c in configurations),
                                 same_inputs=all(m['inputs'] == frames[0]['inputs'] for m in frames),
                                 same_affinity=all(m['arguments']['cpus'] == frames[0]['arguments']['cpus'] for m in frames),
                                 same_java_control=bool(java_frames) and all(m.get('java_control_jar_sha256') and
                                     m.get('java_control_adapter_sha256') and
                                     m['java_control_jar_sha256'] == java_frames[0]['java_control_jar_sha256'] and
                                     m['java_control_adapter_sha256'] == java_frames[0]['java_control_adapter_sha256'] for m in java_frames),
                                 old_has_frozen_classpath=all(m.get('native_control_jar_sha256') and
                                     m.get('native_control_adapter_sha256') for m in old)))
    regression_files = [p for p in root.glob('regression*/runs.jsonl')]
    if regression_files:
        rows = [dict(source_directory=str(path.parent.relative_to(root)), **row)
                for path in sorted(regression_files) for row in read_rows(path)]
        for row in rows:
            reference = next(r for r in golden if int(r['size']) == row['size'] and int(r['mf']) == row['mf'])
            checks.append(dict(phase='regression', size=row['size'], mf=row['mf'], repeat=row['repeat'],
                               all_ok=row['status'] == 'ok',
                               golden_outputs=all(str(row.get(k)) == str(reference.get(k)) for k in fields)))
            all_rows.append(dict(phase='regression', **row))
        expected = {(size, mf, repeat) for size in (4, 8, 12) for mf in (1, 2, 3)
                    if size == 4 or mf != 3 for repeat in (1, 2)}
        checks.append(dict(phase='regression', complete=len(rows) == 14 and {
            (r['size'], r['mf'], r['repeat']) for r in rows} == expected and
            all(r['engine'] == 'native6' for r in rows)))
    valid = len(all_rows) == 44 and all(v for c in checks for k, v in c.items()
                if k not in ('phase', 'runs', 'size', 'mf', 'repeat')) and len(controls) == 2 and all(
                    v for c in controls for k, v in c.items() if k != 'case')
    audit = dict(valid=valid, runs=len(all_rows), fields=fields, cases=checks,
                 controls=controls, interruptions=[dict(directory=str(p.parent.relative_to(root)),
                     **json.loads(p.read_text())) for p in sorted(root.glob('*/interruption.json'))],
                 golden=str(args.golden), golden_sha256=hashlib.sha256(args.golden.read_bytes()).hexdigest())
    (root / 'checks.json').write_text(json.dumps(audit, indent=2) + '\n')
    (root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    keys = sorted(set().union(*(r.keys() for r in all_rows)) - {'command'})
    with (root / 'runs.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=keys)
        writer.writeheader()
        writer.writerows({k: v for k, v in r.items() if k != 'command'} for r in all_rows)
    lookup = {(s['phase'], s['engine']): s for s in summary}
    lines = ['| 输入 | Java s | 旧 native1 s | 旧 native6 s | 新 native1 s | 新 native2 s | 新 native6 s | 自身 1/6 | Java/新 6 |',
             '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for prefix, name in [('first08', '08/MF3'), ('large12', '12/MF3')]:
        selections = [(prefix + '-on', 'java'), (prefix + '-old', 'native1'),
                      (prefix + '-old', 'native6'), (prefix + '-on', 'native1'),
                      (prefix + '-on', 'native2'), (prefix + '-on', 'native6')]
        values = [lookup.get(key, {}).get('total') for key in selections]
        cells = ['—' if v is None else f'{v:.3f}' for v in values]
        ratios = [values[3] / values[5], values[0] / values[5]] if all(
            values[i] is not None for i in (0, 3, 5)) else [None, None]
        lines.append('| ' + name + ' | ' + ' | '.join(cells) + ' | ' +
                     ' | '.join('—' if v is None else f'{v:.3f}×' for v in ratios) + ' |')
    (root / 'tables.md').write_text('\n'.join(lines) + '\n')
    print(json.dumps(dict(valid=valid, runs=len(all_rows), checks=checks), indent=2))
    print('\n'.join(lines))
    if not valid:
        raise SystemExit('Incomplete or mismatching experiment; inspect checks.json.')


if __name__ == '__main__':
    main()

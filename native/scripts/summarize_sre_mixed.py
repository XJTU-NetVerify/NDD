#!/usr/bin/env python3
"""Summarize the completed A/B/D matrix, retaining failed samples."""
import argparse
import csv
import json
import statistics
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('directory', type=Path)
a = p.parse_args()
rows = [json.loads(line) for line in (a.directory / 'runs.jsonl').read_text().splitlines()]
summary = []
checks = []
fields = ['bgp_routes', 'ndd_field_layout', 'link_variable_order_hash', 'link_polarity',
          'semantic_condition_rows', 'semantic_condition_sha256', 'rib_rows', 'rib_sha256',
          'reachability_csv_rows', 'reachability_csv_sha256']
for profile in sorted({r['profile'] for r in rows}):
    for size in sorted({r['size'] for r in rows}):
        for mf in sorted({r['mf'] for r in rows}):
            group = [r for r in rows if (r['profile'], r['size'], r['mf']) == (profile, size, mf)]
            check = dict(profile=profile, size=size, mf=mf, samples=len(group), all_ok=all(r['status'] == 'ok' for r in group))
            check.update({k: len({str(r.get(k)) for r in group if r['status'] == 'ok'}) == 1 for k in fields})
            checks.append(check)
            for engine in ('java', 'native1', 'native6'):
                selected = [r for r in group if r['engine'] == engine]
                successful = [r for r in selected if r['status'] == 'ok']
                out = dict(profile=profile, size=size, mf=mf, engine=engine,
                           samples=len(selected), successful=len(successful),
                           statuses=[r['status'] for r in selected])
                if successful:
                    numeric = set.intersection(*(set(k for k, v in r.items() if type(v) in (int, float)) for r in successful))
                    for k in numeric - {'size', 'mf', 'repeat', 'exitcode', 'ndd_workers'}:
                        out[k] = statistics.mean(r[k] for r in successful)
                    out['total_samples'] = [r['total'] for r in successful]
                summary.append(out)
cross = []
for size in sorted({r['size'] for r in rows}):
    for mf in sorted({r['mf'] for r in rows}):
        selected = [r for r in rows if r['size'] == size and r['mf'] == mf and r['status'] == 'ok']
        keys = ['bgp_routes', 'link_variable_order_hash', 'rib_rows', 'rib_sha256',
                'reachability_csv_rows', 'reachability_csv_sha256']
        cross.append(dict(size=size, mf=mf, **{k: len({str(r.get(k)) for r in selected}) == 1 for k in keys}))
for s in summary:
    group = [r for r in summary if (r['profile'], r['size'], r['mf']) == (s['profile'], s['size'], s['mf'])]
    one = next(r for r in group if r['engine'] == 'native1')
    java = next(r for r in group if r['engine'] == 'java')
    if s['successful'] == s['samples'] and 'total' in s:
        if one['successful'] == one['samples'] and 'total' in one: s['self_speedup'] = one['total'] / s['total']
        if java['successful'] == java['samples'] and 'total' in java: s['java_speedup'] = java['total'] / s['total']
(a.directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
(a.directory / 'checks-all.json').write_text(json.dumps(dict(within_profile=checks, across_profiles=cross), indent=2) + '\n')
keys = sorted(set().union(*(s.keys() for s in summary)))
with (a.directory / 'summary.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, keys); writer.writeheader(); writer.writerows(summary)
lines = ['各值为两次独立进程的算术平均。时间单位秒，RSS 单位 MiB；加速比大于 1 表示变快。', '']
for profile in sorted({r['profile'] for r in rows}):
    lines += [f'## {profile} 组', '', '| fattree / MF | 原 Java | native 1 | native 6 | 自身 1/6 | Java/native 6 |',
              '|---|---:|---:|---:|---:|---:|']
    def value(s, key, digits=3):
        return f'{s[key]:.{digits}f}' if key in s and s['successful'] == s['samples'] else '/'.join(s['statuses'])
    for size in sorted({r['size'] for r in rows}):
        for mf in sorted({r['mf'] for r in rows}):
            g = {s['engine']: s for s in summary if (s['profile'], s['size'], s['mf']) == (profile, size, mf)}
            j, one, six = (g[k] for k in ('java', 'native1', 'native6'))
            lines.append(f'| {size:02d} / {mf} | {value(j,"total")} | {value(one,"total")} | {value(six,"total")} | {value(six,"self_speedup",2)}× | {value(six,"java_speedup",2)}× |')
    lines += ['', '| fattree / MF | Java RSS | native 1 RSS | native 6 RSS |', '|---|---:|---:|---:|']
    for size in sorted({r['size'] for r in rows}):
        for mf in sorted({r['mf'] for r in rows}):
            g = {s['engine']: s for s in summary if (s['profile'], s['size'], s['mf']) == (profile, size, mf)}
            lines.append(f'| {size:02d} / {mf} | {value(g["java"],"peak_rss_mib",1)} | {value(g["native1"],"peak_rss_mib",1)} | {value(g["native6"],"peak_rss_mib",1)} |')
    lines.append('')
lines += ['## 12 / MF3 阶段', '', '| 组别 / 引擎 | 初始化 | SRC | RIB 输出 | 转发 | ACL | 可达性 | GC | Label GC |', '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
for s in summary:
    if s['size'] == 12 and s['mf'] == 3:
        phases = ['init', 'src_compute', 'src_rib_dump', 'spf_fwd', 'spf_acl', 'spf_reachability', 'native_collections', 'native_label_collections']
        vals = [f'{s[k]:.3f}' if k in s else '—' for k in phases]
        lines.append('| ' + s['profile'] + ' / ' + s['engine'] + ' | ' + ' | '.join(vals) + ' |')
(a.directory / 'tables.md').write_text('\n'.join(lines) + '\n')
print(f'{len(rows)} samples; failures: {sum(r["status"] != "ok" for r in rows)}')
print('Within-profile checks:', all(all(v for k,v in c.items() if k not in ('profile','size','mf','samples')) for c in checks))
print('Across-profile output checks:', all(all(v for k,v in c.items() if k not in ('size','mf')) for c in cross))

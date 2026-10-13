#!/usr/bin/env python3
"""Summarize original TSVs only; never runs a benchmark."""
import csv, hashlib, io, json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
records=[];files=[]
known={7:40,8:92,9:352,10:724,11:2680,12:14200,13:73712}
for p in sorted(root.glob('bench_results_*_20260422_*.tsv')):
    data=p.read_bytes();mode='parallel' if '_parallel_' in p.name else 'serial';build='pgo' if '_pgo_' in p.name else 'release'
    # Retain originals byte-for-byte, including upstream's literal \\t separators.
    rows=list(csv.DictReader(io.StringIO(data.decode().replace('\\t','\t')),delimiter='\t'))
    assert len(rows)==42
    files.append(dict(file=p.name,sha256=hashlib.sha256(data).hexdigest(),samples=len(rows)))
    for r in rows:
        n,w=int(r['N']),int(r['workers']);seconds=float(r['elapsed_s']);solutions=int(r['solutions'])
        assert solutions==known[n] and seconds>0
        records.append(dict(mode=mode,build=build,n=n,workers=w,seconds=seconds,solutions=solutions))
for r in records:
    one=next(v for v in records if (v['mode'],v['build'],v['n'],v['workers'])==(r['mode'],r['build'],r['n'],1))
    r['self_speedup']=one['seconds']/r['seconds']
out=root/'results/upstream-20260422';out.mkdir(parents=True,exist_ok=True)
(out/'provenance.json').write_text(json.dumps(dict(source_commit='a8649f58ca1ee4841dd37fca86288dd5809cd12c',samples=len(records),files=files,aggregation='One historical observation per configuration; no new performance runs.',scope='C solver wall after runtime init and before shutdown, including field/formula construction and count; machine metadata incomplete.'),indent=2)+'\n')
with (out/'samples.csv').open('w',newline='') as f:
    writer=csv.DictWriter(f,list(records[0]),lineterminator="\n");writer.writeheader();writer.writerows(records)
lines=['# 原仓库已有 N-Queens 数据（2026-04-22）','','四个原始 sweep，共 168 条历史观测；每配置一次，不提供方差、置信区间或本分支修正后的性能。','']
for mode,build in [('serial','release'),('serial','pgo'),('parallel','release'),('parallel','pgo')]:
    lines += [f'## {mode} / {build}','','| N | 1 worker s | 2 workers s | 4 workers s | 6 workers s | 自身 1/6 | 解数 |','|---:|---:|---:|---:|---:|---:|---:|']
    for n in range(7,14):
        d={r['workers']:r for r in records if (r['mode'],r['build'],r['n'])==(mode,build,n)}
        vals=[f'{d[w]["seconds"]:.3f}' for w in [1,2,4,6]]
        lines.append(f'| {n} | '+ ' | '.join(vals)+f' | {d[6]["self_speedup"]:.2f}× | {known[n]} |')
    lines.append('')
release6=next(r['seconds'] for r in records if (r['mode'],r['build'],r['n'],r['workers'])==('serial','release',13,6))
pgo6=next(r['seconds'] for r in records if (r['mode'],r['build'],r['n'],r['workers'])==('serial','pgo',13,6))
lines+=['serial 表示外围构造顺序执行，操作内部仍并行；parallel 增加外围约束构造的任务。N-Queens 使用 Boolean 终端，不能验证 MT 数值应用的性能。','', f'PGO 不是每个配置都更快：N=13 serial 的六 workers 为 Release {release6:.3f} s、PGO {pgo6:.3f} s，PGO 约慢 {(pgo6/release6-1)*100:.1f}%；原 README 的负号不能解读为改善。','', '不能与 parallel-NDD 的其他机器/版本/计时范围直接相除。原始文件及 SHA256 见 provenance.json。']
(out/'README.md').write_text('\n'.join(lines)+'\n')
print('Summarized',len(records),'existing observations; no benchmark executed.')

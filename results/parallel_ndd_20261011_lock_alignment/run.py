import csv,hashlib,json,random,subprocess,time,statistics,tarfile
from pathlib import Path
root=Path('/home/cheney/NDD'); out=root/'results/parallel_ndd_20261011_lock_alignment';out.mkdir(exist_ok=True)
bins={'original':root/'native/build-release/pndd_nqueens','aligned':root/'native/build-aligned/pndd_nqueens'}
meta={'seed':20261011,'rounds':5,'memory_mib':3072,'cpu_masks':{'1':'0','6':'0,2,4,6,8,10'},'binaries':{k:{'path':str(v),'sha256':hashlib.sha256(v.read_bytes()).hexdigest()} for k,v in bins.items()},'change':'SpinLock alignas(64); engine aligned_alloc; all other production sources identical','clock':'CLOCK_MONOTONIC via Python perf_counter; communicate observes exit; GNU elapsed diagnostic only'}
(out/'metadata.json').write_text(json.dumps(meta,indent=2))
with tarfile.open(out/'aligned_source.tar.gz','w:gz') as a:
 for p in sorted(Path('/tmp/pndd-aligned-source').rglob('*')):
  if p.is_file():a.add(p,arcname=str(p.relative_to('/tmp/pndd-aligned-source')))
rows=[]
for r in range(5):
 configs=[(k,n,w) for k in bins for n,w in [(12,1),(12,6),(13,6)]];random.Random(20261011+r).shuffle(configs)
 for kind,n,w in configs:
  cmd=['taskset','-c',meta['cpu_masks'][str(w)],'/usr/bin/time','-f','rss_kb=%M',str(bins[kind]),str(n),'BCDD',str(w),'0','0','3072','1','0','1','4','2','262144']
  start=time.perf_counter();p=subprocess.run(cmd,capture_output=True,text=True,timeout=120);wall=time.perf_counter()-start
  (out/f'{r+1}_{kind}_{n}_{w}.log').write_text(p.stdout+p.stderr)
  if p.returncode:raise RuntimeError(p.stderr)
  d=json.loads(p.stdout);assert d['solutions']=={12:14200,13:73712}[n]
  d.update(round=r+1,variant=kind,wall_s=wall,rss_kb=int(p.stderr.strip().split('rss_kb=')[-1]),command=cmd);rows.append(d)
  (out/'samples.json').write_text(json.dumps(rows,indent=2));print(r+1,kind,n,w,d['compute_s'],flush=True)
summary=[]
for kind in bins:
 for n,w in [(12,1),(12,6),(13,6)]:
  subset=[d for d in rows if d['variant']==kind and d['n']==n and d['workers']==w]
  summary.append(dict(variant=kind,n=n,workers=w,compute_s=statistics.median(d['compute_s'] for d in subset),wall_s=statistics.median(d['wall_s'] for d in subset),rss_kb=statistics.median(d['rss_kb'] for d in subset)))
(out/'summary.json').write_text(json.dumps(summary,indent=2))

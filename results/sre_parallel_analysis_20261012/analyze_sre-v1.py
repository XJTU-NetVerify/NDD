#!/usr/bin/env python3
"""Sequential SRE ablations. Profiles perturb timings and are never averaged."""
import argparse, collections, csv, hashlib, json, os, random, re, resource
import signal, statistics, subprocess, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--phase', choices=['profile', 'matrix', 'hash', 'confirm', 'larger'], required=True)
p.add_argument('--repeats', type=int, default=2)
p.add_argument('--timeout', type=int, default=180)
p.add_argument('--sre', type=Path, default=Path('/home/cheney/SRE'))
a = p.parse_args(); a.out = a.out.resolve(); a.sre = a.sre.resolve()
java='/home/cheney/.cache/ndd-bench-tools/sysroot/usr/lib/jvm/java-17-openjdk-amd64/bin/java'
cp=str(a.sre/'target/classes')+':'+(a.sre/'target/cp.txt').read_text().strip()
configs={
 'base1': ('baseline',1,{}), 'base6': ('baseline',6,{}),
 'pool6': ('baseline',6,{'nddParallel':'false'}),
 'coarse6': ('baseline',6,{'threshold':'64'}),
 'labels6': ('baseline',6,{'nddParallel':'false','labels':'true','depth':'2'}),
 'both6': ('baseline',6,{'labels':'true','depth':'2'}),
 'count1': ('count',1,{}), 'and1': ('and',1,{}),
 'sycache1': ('sycache',1,{}), 'opt1': ('opt',1,{}), 'opt6': ('opt',6,{}),
 'prof1': ('profile',1,{'profile':'true'}), 'prof6': ('profile',6,{'profile':'true'}),
 'optprof1': ('opt-profile',1,{'profile':'true'}), 'optprof6': ('opt-profile',6,{'profile':'true'}),
 'hash1': ('baseline',1,{}), 'hash6': ('baseline',6,{}),
 'hashcount1': ('count',1,{}), 'hashcount6': ('count',6,{}),
 'hashopt1': ('opt',1,{}), 'hashopt6': ('opt',6,{}),
}
names={'profile':['prof1','prof6','optprof1','optprof6'],
       'matrix':['base1','base6','pool6','coarse6','labels6','both6','count1','and1','sycache1','opt1','opt6'],
       'hash':['base1','base6','hash1','hash6','hashcount1','hashcount6','hashopt1','hashopt6'],
       'confirm':['base1','base6','hashcount1','hashcount6'], 'larger':['hashcount1','hashcount6']}[a.phase]
size,mf=(12,2) if a.phase=='larger' else (8,3) if a.phase=='confirm' else (8,2)
refs=[json.loads(x) for x in (ROOT/'results/sre_parallel_20261011_final/runs.jsonl').read_text().splitlines()]
ref=next(r for r in refs if r['size']==size and r['mf']==mf)
directory=a.out/a.phase; directory.mkdir(exist_ok=False); (directory/'logs').mkdir()
meta=dict(arguments={k:str(v) if isinstance(v,Path) else v for k,v in vars(a).items()},
          configs={k:configs[k] for k in names},timestamp=time.strftime('%Y-%m-%dT%H:%M:%S%z'),
          libraries={name:hashlib.sha256((a.out/'libs'/f'{name}.so').read_bytes()).hexdigest() for name in {configs[k][0] for k in names}},
          sre_adapter_sha256=hashlib.sha256((a.sre/'target/classes/sre/ndd/NDD.class').read_bytes()).hexdigest(),
          methodology='Fresh processes, sequential shuffled paired rounds, CPU 0,2,4,6,8,10; F Header-Link plan1 original trim; Xmx8g / native3GiB / RSS12GiB; profiles inclusive and perturbed; prototypes compile-time opt-in.')
(directory/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
schedule=[]
for repeat in range(1,(1 if a.phase=='profile' else a.repeats)+1):
 order=list(names); random.Random(20261012+repeat).shuffle(order)
 schedule.extend((n,repeat) for n in order)
(directory/'schedule.json').write_text(json.dumps(schedule,indent=2)+'\n')
rows=[]
for name,repeat in schedule:
 lib,workers,props=configs[name]
 properties=dict(engine='native',workers=str(workers),labels='false',memoryMiB='3072',cache='262144',threshold='4',chunk='2',depth='4',collectEvery='16384',checksum='true')
 properties.update(props)
 classpath=(str(a.out/'hash-classes')+':'+cp) if name.startswith('hash') else cp
 cmd=['taskset','-c','0,2,4,6,8,10',java,'-Xmx8g']+[f'-Dsre.ndd.{k}={v}' for k,v in properties.items()]+['-Dpndd.library='+str(a.out/'libs'/f'{lib}.so'),'-cp',classpath,'application.config2spec.NDDConfig2spec',f'bgp_fattree{size:02d}',str(mf),'backend=f','order=header-link','plan=1']
 if a.phase=='hash':
  cmd.insert(5,'-javaagent:'+str(a.out/'hash-audit.jar'))
  cmd.insert(5,'-Dsre.ndd.auditFile='+str(directory/'logs'/f'{name}-r{repeat}-hash.json'))
 log=directory/'logs'/f'{name}-r{repeat}.log'
 rib=a.sre/f'networks/exp/fattree/bgp/bgp_fattree{size:02d}/OutNDD/test/routes_0_execute'
 if rib.exists(): rib.unlink()
 before=resource.getrusage(resource.RUSAGE_CHILDREN); started=time.monotonic(); peak=0; reason=None
 with log.open('w') as stream:
  proc=subprocess.Popen(cmd,cwd=a.sre,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
  while proc.poll() is None:
   try:
    status=Path(f'/proc/{proc.pid}/status').read_text()
    m=re.search(r'^VmHWM:\s+(\d+)',status,re.M)
    if m: peak=max(peak,int(m.group(1)))
    if peak>12288*1024: reason='rss_limit'
   except FileNotFoundError: pass
   if time.monotonic()-started>a.timeout: reason='timeout'
   if reason:
    os.killpg(proc.pid,signal.SIGTERM)
    try: proc.wait(timeout=3)
    except subprocess.TimeoutExpired: os.killpg(proc.pid,signal.SIGKILL)
    break
   time.sleep(.05)
  code=proc.wait()
 after=resource.getrusage(resource.RUSAGE_CHILDREN); wall=time.monotonic()-started
 r=dict(size=size,mf=mf,config=name,repeat=repeat,status=reason or ('ok' if code==0 else 'error'),exitcode=code,wall_seconds=wall,peak_rss_mib=peak/1024,
        cpu_seconds=(after.ru_utime-before.ru_utime)+(after.ru_stime-before.ru_stime),command=cmd)
 for k,v in re.findall(r'^([a-z][a-z0-9_]*):(.+)$',log.read_text(errors='replace'),re.M):
  try: r[k]=float(v) if any(c in v for c in '.eE') else int(v)
  except ValueError: r[k]=v
 m=re.search(r'^(\d+) bgp route$',log.read_text(),re.M)
 if m:r['bgp_routes']=int(m.group(1))
 fields=['bgp_routes','ndd_field_layout','link_variable_order_hash','link_polarity','semantic_condition_rows','semantic_condition_sha256']
 r['semantic_ok']=r['status']=='ok' and all(str(r.get(k))==str(ref.get(k)) for k in fields)
 if r['status']=='ok' and not r['semantic_ok']: r['status']='mismatch'
 if rib.exists() and r['status']=='ok':
  lines=sorted(rib.read_text().splitlines());r['rib_rows']=len(lines)
  r['rib_sha256']=hashlib.sha256(('\n'.join(lines)+'\n').encode()).hexdigest()
 r['mean_cpu_cores']=r['cpu_seconds']/wall
 rows.append(r)
 with (directory/'runs.jsonl').open('a') as stream:stream.write(json.dumps(r)+'\n')
 print(f'{len(rows)}/{len(schedule)} {name} r{repeat}: {r["status"]} total={r.get("total")}s cpu={r["mean_cpu_cores"]:.2f} RSS={r["peak_rss_mib"]:.1f}',flush=True)
 if r['status']=='mismatch':raise SystemExit('Semantic mismatch; stop immediately.')
keys=sorted(set().union(*(r.keys() for r in rows)) - {'command'})
with (directory/'runs.csv').open('w',newline='') as stream:
 writer=csv.DictWriter(stream,fieldnames=keys);writer.writeheader();writer.writerows({k:v for k,v in r.items() if k!='command'} for r in rows)
summary=[]
for name in names:
 group=[r for r in rows if r['config']==name]; good=[r for r in group if r['status']=='ok']
 out=dict(config=name,size=size,mf=mf,samples=len(group),successful=len(good))
 for key in keys:
  if key in ['config','size','mf','repeat','status','exitcode','semantic_ok']:continue
  vals=[r[key] for r in good if isinstance(r.get(key),(int,float))]
  if vals:out[key]=statistics.mean(vals)
 summary.append(out)
(directory/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
rib_hashes={r.get('rib_sha256') for r in rows if r['status']=='ok'}
(directory/'checks.json').write_text(json.dumps(dict(semantic_ok=all(r['semantic_ok'] for r in rows),rib_outputs_match=len(rib_hashes)==1 and None not in rib_hashes),indent=2)+'\n')

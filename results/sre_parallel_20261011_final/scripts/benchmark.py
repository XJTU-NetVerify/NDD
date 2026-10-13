#!/usr/bin/env python3
"""Fresh-process F-profile SRE matrix. Never build concurrently with this script."""
import argparse, csv, hashlib, json, os, random, re, signal, subprocess, tarfile, time
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--ndd', type=Path, default=Path('/home/cheney/NDD'))
p.add_argument('--java', default=os.environ.get('JAVA_HOME', '/home/cheney/.cache/ndd-bench-tools/sysroot/usr/lib/jvm/java-17-openjdk-amd64') + '/bin/java')
p.add_argument('--library', type=Path)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--cpus', default='0,2,4,6,8,10')
p.add_argument('--repeats', type=int, default=2)
p.add_argument('--timeout', type=float, default=900)
p.add_argument('--rss-limit-mib', type=int, default=12288)
p.add_argument('--seed', type=int, default=20261011)
p.add_argument('--sizes', default='4,8,12')
p.add_argument('--mfs', default='1,2,3')
p.add_argument('--engines', default='java,native1,native6')
p.add_argument('--cache', type=int, default=262144)
p.add_argument('--collect-every', type=int, default=16384)
a = p.parse_args()
sre = Path(__file__).resolve().parents[2]
a.ndd = a.ndd.resolve(); a.output = a.output.resolve()
lib = (a.library or a.ndd / 'native/build-sre/libpnddjni.so').resolve()
if a.output.exists() and (a.output / 'runs.jsonl').exists():
    raise SystemExit('Choose a new output directory; existing runs are never overwritten.')
a.output.mkdir(parents=True, exist_ok=True)
(a.output / 'logs').mkdir(exist_ok=True)
cp = str(sre / 'target/classes') + ':' + (sre / 'target/cp.txt').read_text().strip()

def command(cmd, cwd=None):
    return subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=True).stdout.strip()

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1048576), b''): h.update(chunk)
    return h.hexdigest()

meta = dict(arguments={k: str(v) if isinstance(v, Path) else v for k,v in vars(a).items()},
            timestamp=time.strftime('%Y-%m-%dT%H:%M:%S%z'), java=command([a.java,'-version']),
            cpu=command(['lscpu']), compiler=command(['gcc','--version']),
            native_library_sha256=sha(lib), ndd_jar_sha256=sha(Path.home()/'.m2/repository/org/ants/ndd/1.0.1/ndd-1.0.1.jar'),
            configuration=dict(profile='F / all BCDD',order='header-link',plan=1,trim=True,
                java_heap='8 GiB',native_diagram_budget_mib=3072,cache_entries=a.cache,
                threshold=4,chunk=2,depth=4,label_parallel=False,ndd_parallel=True,
                adapter_collect_every=a.collect_every,native_gc_interval=0,label_order='legacy LSB-first',
                rss_limit_mib=a.rss_limit_mib,clock='time.monotonic',sampling_seconds=.05,
                total_excludes='JVM startup, post-timing checksum and native close',
                wall_includes='startup, checksum and close'))
for name, repo in [('ndd',a.ndd),('sre',sre)]:
    meta[name+'_commit']=command(['git','rev-parse','HEAD'],repo)
    (a.output/(name+'.diff')).write_text(command(['git','diff','--binary','HEAD'],repo)+'\n')
    with tarfile.open(a.output/(name+'-sources.tar.gz'),'w:gz') as tar:
        paths = [repo/'pom.xml', repo/'README.md', repo/'src']
        paths += [repo/'native'] if name=='ndd' else [repo/'experiments/parallel-ndd']
        for root in paths:
            if root.is_file(): tar.add(root,arcname=str(root.relative_to(repo)))
            elif root.exists():
                for f in sorted(root.rglob('*')):
                    if f.is_file() and not any(x.startswith('build') or x=='__pycache__' for x in f.relative_to(root).parts):
                        tar.add(f,arcname=str(f.relative_to(repo)))
inputs=[]
for size in map(int,a.sizes.split(',')):
    base=sre/f'networks/exp/fattree/bgp/bgp_fattree{size:02d}'
    for f in sorted(base.rglob('*')):
        if f.is_file() and 'OutNDD' not in f.relative_to(base).parts:
            inputs.append(dict(path=str(f.relative_to(sre)),sha256=sha(f)))
meta['inputs']=inputs
(a.output/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
rows=[]

def run(size,mf,kind,repeat):
    tag=f'fattree{size:02d}-mf{mf}-{kind}-r{repeat}'
    workers=6 if kind=='native6' else 1
    cmd=['taskset','-c',a.cpus,a.java,'-Xmx8g','-Dsre.ndd.checksum=true',
        '-Dsre.ndd.engine='+('java' if kind=='java' else 'native'),f'-Dsre.ndd.workers={workers}',
        '-Dsre.ndd.labels=false','-Dsre.ndd.memoryMiB=3072',f'-Dsre.ndd.cache={a.cache}',
        '-Dsre.ndd.threshold=4','-Dsre.ndd.chunk=2','-Dsre.ndd.depth=4',f'-Dsre.ndd.collectEvery={a.collect_every}',
        '-Dpndd.library='+str(lib),'-cp',cp,'application.config2spec.NDDConfig2spec',
        f'bgp_fattree{size:02d}',str(mf),'backend=f','order=header-link','plan=1']
    spec=sre/f'networks/exp/fattree/bgp/bgp_fattree{size:02d}/OutNDD/spec/{mf}'
    # The application's output directory is shared across fresh processes.
    # Remove old CSVs only, so a failed run cannot inherit a prior result.
    if spec.exists():
        for old in spec.glob('*.csv'): old.unlink()
    started=time.monotonic(); peak=0; timed_peak=0; reason=None
    log=a.output/'logs'/f'{tag}.log'
    with log.open('wb') as out:
        proc=subprocess.Popen(cmd,cwd=sre,stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
        while proc.poll() is None:
            try:
                status=Path(f'/proc/{proc.pid}/status').read_text()
                rss=int(re.search(r'VmHWM:\s+(\d+)',status).group(1))
                peak=max(peak,rss)
                if b'\ntotal:' not in log.read_bytes(): timed_peak=max(timed_peak,rss)
                if rss > a.rss_limit_mib*1024: reason='rss_limit'
            except (FileNotFoundError,AttributeError,ProcessLookupError): pass
            if time.monotonic()-started>a.timeout: reason='timeout'
            if reason:
                os.killpg(proc.pid,signal.SIGTERM)
                try: proc.wait(timeout=3)
                except subprocess.TimeoutExpired: os.killpg(proc.pid,signal.SIGKILL)
                break
            time.sleep(.05)
        exitcode=proc.wait()
    wall=time.monotonic()-started; text=log.read_text(errors='replace')
    r=dict(size=size,mf=mf,engine=kind,repeat=repeat,status=reason or ('ok' if exitcode==0 else 'error'),
        exitcode=exitcode,wall_seconds=wall,peak_rss_mib=peak/1024,timed_peak_rss_mib=timed_peak/1024,
        log=str(log.relative_to(a.output)),command=cmd)
    for key,value in re.findall(r'^([a-z][a-z0-9_]*):(.+)$',text,re.M):
        try: r[key]=float(value) if any(x in value for x in '.eE') else int(value)
        except ValueError: r[key]=value
    count=re.search(r'^(\d+) bgp route$',text,re.M)
    if count: r['bgp_routes']=int(count.group(1))
    normalized=[]
    if spec.exists():
        for f in sorted(spec.glob('*.csv')):
            with f.open(newline='') as stream: data=list(csv.reader(stream))
            normalized.append([f.name,data[0] if data else [],sorted(data[1:])])
    r['reachability_csv_rows']=sum(len(item[2]) for item in normalized)
    r['reachability_csv_sha256']=hashlib.sha256(json.dumps(normalized,separators=(',',':')).encode()).hexdigest()
    if r['status']=='ok' and ('semantic_condition_sha256' not in r or 'total' not in r or not normalized): r['status']='incomplete'
    with (a.output/'runs.jsonl').open('a') as f: f.write(json.dumps(r)+'\n')
    previous=[old for old in rows if old['size']==size and old['mf']==mf and old['status']=='ok']
    rows.append(r)
    fields=['bgp_routes','ndd_field_layout','link_variable_order_hash','link_polarity','semantic_condition_rows','semantic_condition_sha256','reachability_csv_rows','reachability_csv_sha256']
    mismatch=[key for key in fields if r['status']=='ok' and any(str(old.get(key))!=str(r.get(key)) for old in previous)]
    if mismatch: raise SystemExit(f'Semantic mismatch at {tag}: {mismatch}; stop before further measurements.')
    print(f'{len(rows):02d} {tag}: {r["status"]} total={r.get("total")}s wall={wall:.3f}s RSS={peak/1024:.1f}MiB',flush=True)

schedule=[]
for repeat in range(1,a.repeats+1):
    cases=[(s,m,k,repeat) for s in map(int,a.sizes.split(',')) for m in map(int,a.mfs.split(',')) for k in a.engines.split(',')]
    random.Random(a.seed+repeat).shuffle(cases); schedule.extend(cases)
(a.output/'schedule.json').write_text(json.dumps(schedule,indent=2)+'\n')
for case in schedule: run(*case)
keys=sorted(set().union(*(r.keys() for r in rows)) - {'command'})
with (a.output/'runs.csv').open('w',newline='') as f:
    writer=csv.DictWriter(f,keys); writer.writeheader(); writer.writerows({k:v for k,v in r.items() if k!='command'} for r in rows)
checks=[]
for size in map(int,a.sizes.split(',')):
    for mf in map(int,a.mfs.split(',')):
        group=[r for r in rows if r['size']==size and r['mf']==mf]
        fields=['bgp_routes','ndd_field_layout','link_variable_order_hash','link_polarity','semantic_condition_rows','semantic_condition_sha256','reachability_csv_rows','reachability_csv_sha256']
        check=dict(size=size,mf=mf,all_ok=all(r['status']=='ok' for r in group))
        check.update({key:len({str(r.get(key)) for r in group})==1 for key in fields})
        checks.append(check)
(a.output/'checks.json').write_text(json.dumps(checks,indent=2)+'\n')
if not all(all(v for k,v in c.items() if k not in ('size','mf')) for c in checks):
    raise SystemExit('One or more runs failed or semantic checks differ; inspect checks.json and logs.')

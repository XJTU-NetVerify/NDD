#!/usr/bin/env python3
"""Validate C-NDD against native ListDD in the same pinned latest frontend."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--work', type=Path, required=True)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--model', type=Path, help='Default: pinned upstream examples/gear.1.etf')
p.add_argument('--expected-states', type=int, help='Required for custom models')
a = p.parse_args()
a.work = a.work.resolve(); a.out = a.out.resolve()
a.out.mkdir(parents=True, exist_ok=False)
model = a.model.resolve() if a.model else a.work / 'ltsmin-src/examples/gear.1.etf'
expected = a.expected_states if a.model else 2689  # Upstream testsuite/check-ETF/test1.exp
if expected is None: raise SystemExit('Custom model requires --expected-states')
frontend = 'etf2lts-sym' if model.suffix == '.etf' else 'dve2lts-sym'
binary = a.work / 'ltsmin-build/src/pins2lts-sym' / frontend
test = a.work / 'ltsmin-build/src/tests/test-cndd'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
def save(name, value): (a.out/name).write_text(json.dumps(value, indent=2)+'\n')
provenance = json.loads((a.work/'ltsmin-src/cndd-provenance.json').read_text())
assert provenance['upstream']['commit'] == '07f9bf8aa8957cab4f8a47c1a943bf7fef968288'
save('identity.json', {'provenance':provenance, 'platform':os.uname().sysname,
     'files':{str(f):sha(f) for f in (binary,test,model,Path(__file__))},
     'expected_states':expected, 'performance_acceptance':'not_evaluated'})
rows=[]
def run(name, command, trace=False, allowed=(0,)):
    command=list(map(str,command)); env=dict(os.environ); env.pop('LTSMIN_LAYER_TRACE',None)
    if trace:
        directory=a.out/(name+'-tuples'); directory.mkdir(); env['LTSMIN_LAYER_TRACE']=str(directory)
    save(name+'-command.json', {'argv':command,'trace':env.get('LTSMIN_LAYER_TRACE'),'timeout_seconds':600})
    with (a.out/(name+'.log')).open('w') as log:
        proc=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,env=env,start_new_session=True)
        try: rc=proc.wait(timeout=600)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid,signal.SIGKILL); proc.wait(); rc=124
    text=(a.out/(name+'.log')).read_text(errors='replace')
    row={'name':name,'exit':rc}; rows.append(row); save('runs.json',rows)
    assert rc in allowed, (name,rc,text[-2000:])
    assert not re.search(r'Segmentation fault|Assertion.*failed|AddressSanitizer|LeakSanitizer|runtime error:|lace fatal',text,re.I),name
    print(name,'exit',rc,flush=True)
    return text,row
version,_=run('version',[binary,'--version'],allowed=(0,255))
assert 'v3.1.0-dev-cndd-ltsmin-07f9bf8aa895-' in version,version
for worker in (1,2,4):
    for gc in (0,11):
        text,_=run(f'adapter-w{worker}-gc{gc}',[test,f'--cndd-workers={worker}',
                      f'--cndd-gc-interval={gc}','--cndd-memory-mib=32','--cndd-stats'])
        assert 'CNDD_ADAPTER_PASS repeats=3' in text
        cleanup=re.findall(r'nodes_after_cleanup=(\d+) baseline_nodes=(\d+)',text)
        assert len(cleanup)==6 and all(n==b for n,b in cleanup),cleanup
text,_=run('invalid-workers',[test,'--cndd-workers=0'],allowed=(1,255))
assert 'Invalid C-NDD runtime option' in text
cases=[('listdd',['--vset=ldd'])]
for w in (1,2,4): cases.append((f'cndd-w{w}',['--vset=cndd',f'--cndd-workers={w}','--cndd-stats']))
cases.append(('cndd-forced-gc',['--vset=cndd','--cndd-workers=1','--cndd-memory-mib=32','--cndd-gc-interval=11','--cndd-stats']))
baseline=None
for name,options in cases:
    text,row=run(name,[binary,*options,'--order=bfs-prev','-rgs','--when','-v',model],trace=True)
    states=re.findall(r'state space has(?: precisely)? ([\d.e+]+) states',text)
    levels=re.findall(r'level (\d+) is finished',text)
    calls=re.findall(r'(\d+) group checks(?: and|,) (\d+) next state calls',text)
    assert states and int(float(states[-1]))==expected and levels and calls,name
    metrics=(expected,max(map(int,levels)),*map(int,calls[-1]))
    if baseline is None: baseline=metrics
    assert metrics==baseline,(name,metrics,baseline)
    row.update(states=metrics[0],max_level=metrics[1],checks=metrics[2],next_calls=metrics[3])
    if name.startswith('cndd'):
        stats=re.findall(r'CNDD_STATS ndd_gc=(\d+) label_gc=(\d+) nodes_after_cleanup=(\d+) baseline_nodes=(\d+)',text)
        assert len(stats)==1 and stats[0][2]==stats[0][3],(name,stats)
        row.update(ndd_gc=int(stats[0][0]),label_gc=int(stats[0][1]))
        if name=='cndd-forced-gc': assert row['ndd_gc']>1 and row['label_gc']>1,row
    save('runs.json',rows)
def tuples(name,level,kind):
    f=a.out/(name+'-tuples')/f'{level:03d}-{kind}.csv'
    lines=f.read_text().splitlines(); result={tuple(map(int,line.split(','))) for line in lines}
    assert len(result)==len(lines),('duplicates',str(f))
    return result
comparisons=[]
for name,_ in cases:
    assert len(list((a.out/(name+'-tuples')).glob('*.csv')))==2*(baseline[1]+1)
    prior=set()
    for level in range(baseline[1]+1):
        frontier=tuples(name,level,'frontier'); visited=tuples(name,level,'visited')
        assert not(prior & frontier) and prior | frontier==visited,(name,level,'BFS invariant')
        for kind,actual in [('frontier',frontier),('visited',visited)]:
            assert actual==tuples('listdd',level,kind),(name,level,kind)
        comparisons.append({'backend':name,'level':level,'frontier':len(frontier),'visited':len(visited)})
        prior=visited
    assert len(prior)==expected
save('tuple-comparison.json',comparisons)
save('summary.json',{'status':'PASS','upstream_commit':provenance['upstream']['commit'],'model':str(model),
     'metrics':baseline,'adapter_cases':6,'model_cases':5,'tuple_oracle':'native ListDD in same latest frontend',
     'performance_acceptance':'not_evaluated'})
print('LATEST_LTSMIN_CNDD_PASS',flush=True)

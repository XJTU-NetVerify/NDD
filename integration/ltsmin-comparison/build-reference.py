#!/usr/bin/env python3
"""Build the historical three reference adapters on pinned LTSmin master.

Use a fresh work directory after measured processes have finished. Runtime
libraries and adapter sources come from the frozen historical refcontrol.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

def audit(work):
    binary = work/'build/src/pins2lts-sym/dve2lts-sym'
    config = (work/'build/src/hre/config.h').read_text()
    assert all('#define '+flag in config for flag in ('HAVE_SYLVAN', 'HAVE_BUDDY'))
    names = [l.split()[-1] for l in subprocess.check_output(['nm','-g','--defined-only',str(binary)],text=True).splitlines() if l.split()]
    for name in ('vdom_create_sylvan','vdom_create_lddmc','vdom_create_fdd','lace_init','lace_startup','sylvan_init_package'):
        assert names.count(name) == 1, name
    assert not any(n.startswith(('mtpndd_','pndd_mc_')) for n in names)
    identity = json.loads((work/'identity.json').read_text())
    if 'affinity_enforcement' in identity:
        assert names.count('__wrap_hwloc_topology_load') == 1
    identity['binary_sha256'] = hashlib.sha256(binary.read_bytes()).hexdigest()
    identity['audit_program_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    (work/'identity.json').write_text(json.dumps(identity,indent=2)+'\n')
    (work/'status.json').write_text(json.dumps({'status':'BUILT_LINK_AUDIT_PASS','semantic_preflight':'PENDING'})+'\n')
    print('REFERENCE_BUILD_PASS',flush=True)

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--work', type=Path, required=True)
p.add_argument('--upstream', type=Path, required=True)
p.add_argument('--legacy', type=Path, required=True)
p.add_argument('--sylvan-prefix', type=Path, required=True)
p.add_argument('--dependency-prefix', type=Path, required=True)
p.add_argument('--audit-only', action='store_true', help='audit an already linked build without rebuilding')
a = p.parse_args()
if a.audit_only:
    audit(a.work.resolve())
    raise SystemExit()
repo = Path(__file__).resolve().parents[2]
sha = lambda f: hashlib.sha256(f.read_bytes()).hexdigest()
locked = json.loads((repo/'integration/ltsmin-cndd/upstream.json').read_text())
tree = hashlib.sha256()
for f in sorted(a.upstream.rglob('*')):
    if f.is_file() and '.git' not in f.relative_to(a.upstream).parts:
        tree.update(str(f.relative_to(a.upstream)).encode()+b'\0'); tree.update(f.read_bytes())
assert tree.hexdigest() == locked['source_tree_sha256'], 'unexpected upstream tree'
# A build on another core can disturb measured cache/memory behavior too.
for proc in Path('/proc').iterdir():
    if not proc.name.isdigit(): continue
    try:
        assert (proc/'comm').read_text().strip() != 'dve2lts-sym', 'measured checker still active'
    except FileNotFoundError: pass
a.work = a.work.resolve(); a.work.parent.mkdir(parents=True, exist_ok=True); a.work.mkdir(exist_ok=False)
src = a.work/'src'; build = a.work/'build'; logs = a.work/'logs'
shutil.copytree(a.upstream, src, ignore=shutil.ignore_patterns('.git'))
build.mkdir(); logs.mkdir()
identity = {'upstream': locked, 'legacy_source': str(a.legacy), 'adapter_sha256': {},
            'libraries': {}, 'scope': 'historical reference libraries/adapters, pinned master frontend; no C-NDD runtime'}
for name in ('vset_sylvan.c', 'vset_lddmc.c', 'vset_buddy.c'):
    f = a.legacy/'src/vset-lib'/name
    shutil.copy2(f, src/'src/vset-lib'/name)
    identity['adapter_sha256'][name] = sha(f)
# These are the historical adapters, not master's Sylvan-1.8 adapters. Keep
# their original library contract rather than silently omitting Sylvan or
# upgrading the reference implementation under the old benchmark settings.
configure = src/'configure.ac'
text = configure.read_text()
old = 'sylvan >= 1.8.1 sylvan < 1.9'
assert text.count(old) == 1
configure.write_text(text.replace(old, 'sylvan >= 1.1 sylvan < 1.2'))
identity['configure_compatibility'] = {'old_requirement': old, 'new_requirement': 'sylvan >= 1.1 sylvan < 1.2',
                                       'reason': 'three historical adapters and exact historical 1.1.2 library retained'}
# Match the proven old frontend's Lace startup/call convention. This keeps
# the historical runtime unchanged; all benchmark entrypoints run on a Lace
# worker, and the ordinary C helpers acquire its deque via LACE_ME as before.
compat = src/'src/hre/legacy_lace_compat.h'
compat.write_text('''#ifndef LTSMIN_LEGACY_LACE_COMPAT_H
#define LTSMIN_LEGACY_LACE_COMPAT_H
#include <lace.h>
#ifndef RUN
#define RUN(f, ...) ({ LACE_ME; CALL(f, ##__VA_ARGS__); })
#endif
#endif
''')
patched = []
for path in (src/'src').rglob('*.c'):
    text = path.read_text()
    if 'RUN(' in text:
        text = '#include <hre/config.h>\n#ifdef HAVE_SYLVAN\n#include <hre/legacy_lace_compat.h>\n#endif\n' + text
        path.write_text(text); patched.append(str(path.relative_to(src)))
for relative in ('src/pins2lts-sym/pins2lts-sym.c', 'src/ldd2bdd/ldd2bdd.c'):
    path = src/relative; text = path.read_text()
    old = 'lace_set_stacksize(lace_stacksize);\n    lace_start(lace_n_workers, lace_dqsize);\n    RUN(actual_main, &args);'
    assert text.count(old) == 1, relative
    path.write_text(text.replace(old, 'lace_init(lace_n_workers, lace_dqsize);\n    lace_startup(lace_stacksize, TASK(actual_main), (void*)&args);'))
path = src/'src/spg/spgsolver.c'; text = path.read_text()
old = 'lace_set_stacksize(stacksize);\n        lace_start(lace_n_workers, lace_dqsize);'
assert text.count(old) == 1
path.write_text(text.replace(old, 'lace_init(lace_n_workers, lace_dqsize);\n        lace_startup(stacksize, 0, 0);'))
identity['lace_frontend_compatibility'] = {'header_sha256': sha(compat), 'run_helper_files': patched,
                                         'startup_reference': str(a.legacy/'src/pins2lts-sym/pins2lts-sym.c')}
# Build the symbolic benchmark frontend and its libraries, not unrelated
# executables with different runtime/API/link requirements. Sources stay intact.
path = src/'src/Makefile.am'; text = path.read_text()
keep = {'hre-io','util-lib','hre','hre-mpi','dm','bignum','ltsmin-lib','andl-lib','lts-io',
        'lts-lib','pins-lib','prob-lib','mc-lib','vset-lib','spg-lib','pins2lts-sym'}
excluded = []
lines = []
for line in text.splitlines():
    if line.startswith('SUBDIRS += ') and line.removeprefix('SUBDIRS += ') not in keep:
        excluded.append(line.removeprefix('SUBDIRS += ')); line = '# benchmark-only build: '+line
    lines.append(line)
path.write_text('\n'.join(lines)+'\n')
identity['excluded_tools'] = excluded
for prefix in (a.sylvan_prefix, a.dependency_prefix):
    for pattern in ('lib/*.a', 'lib/pkgconfig/*.pc'):
        for f in prefix.glob(pattern): identity['libraries'][str(f)] = sha(f)
(src/'src/hre/git_version.h').write_text('#define GIT_VERSION "v3.1.0-dev-refcontrol-07f9bf8"\n')
env = dict(os.environ)
env.update(PKG_CONFIG_PATH=f'{a.sylvan_prefix}/lib/pkgconfig:{a.dependency_prefix}/lib/pkgconfig',
           CPPFLAGS=f'-I{a.sylvan_prefix}/include -I{a.dependency_prefix}/include',
           LDFLAGS=f'-L{a.sylvan_prefix}/lib -L{a.dependency_prefix}/lib')
# Historical Lace enumerates the whole topology and otherwise widens the
# inherited taskset mask when binding worker0. Restrict its topology to that
# inherited binding, without changing the historical library or DD settings.
affinity_source = a.work/'reference_affinity.c'
affinity_source.write_text('''#include <hwloc.h>
extern int __real_hwloc_topology_load(hwloc_topology_t);
int __wrap_hwloc_topology_load(hwloc_topology_t topology) {
    /* Loading itself may temporarily bind to every PU for CPUID discovery.
     * Restrict before loading, not just after it has already moved threads. */
    unsigned long flags = hwloc_topology_get_flags(topology)
        | HWLOC_TOPOLOGY_FLAG_IS_THISSYSTEM
        | HWLOC_TOPOLOGY_FLAG_RESTRICT_TO_CPUBINDING
        | HWLOC_TOPOLOGY_FLAG_DONT_CHANGE_BINDING;
    int result = hwloc_topology_set_flags(topology, flags);
    if (result) return result;
    result = __real_hwloc_topology_load(topology);
    if (result) return result;
    hwloc_bitmap_t binding = hwloc_bitmap_alloc();
    if (!binding) return -1;
    result = hwloc_get_cpubind(topology, binding, HWLOC_CPUBIND_THREAD);
    if (!result) result = hwloc_topology_restrict(topology, binding, 0);
    hwloc_bitmap_free(binding);
    return result;
}
''')
hwloc_cflags = subprocess.check_output(['pkg-config','--cflags','hwloc'],env=env,text=True).split()
affinity_object = a.work/'reference_affinity.o'
affinity_compile = ['gcc','-O2','-g',*hwloc_cflags,'-c',str(affinity_source),'-o',str(affinity_object)]
subprocess.run(affinity_compile,check=True)
env['LIBS'] = str(affinity_object)+' -lhwloc'
env['LDFLAGS'] += ' -Wl,--wrap=hwloc_topology_load'
identity['affinity_enforcement'] = {'scope':'preserve inherited CPU mask instead of permitting historical Lace to widen it',
                                  'source_sha256':sha(affinity_source),'object_sha256':sha(affinity_object),'compile':affinity_compile}
identity['hwloc_pkg_version'] = subprocess.check_output(['pkg-config','--modversion','hwloc'],env=env,text=True).strip()
identity['environment'] = {k:env[k] for k in ('PKG_CONFIG_PATH','CPPFLAGS','LDFLAGS','LIBS')}
identity['sylvan_pkg_version'] = subprocess.check_output(['pkg-config','--modversion','sylvan'],env=env,text=True).strip()
assert identity['sylvan_pkg_version'] == '1.1.2'
commands = [(['autoreconf','-fi'],src),
            ([str(src/'configure'),'--enable-pkgconf-static','--disable-dist','--disable-scoop',
              '--disable-opaal','--disable-prob','--disable-pnml','--without-spot','--without-spins',
              'CC=gcc','CXX=g++','CFLAGS=-O2 -g -fcommon -std=gnu11','CXXFLAGS=-O2 -g'],build),
            (['make','-j4'],build)]
identity['commands'] = [{'argv':cmd,'cwd':str(cwd)} for cmd,cwd in commands]
(a.work/'identity.json').write_text(json.dumps(identity,indent=2)+'\n')
for stage,(cmd,cwd) in zip(('autoreconf','configure','build'),commands):
    with (logs/(stage+'.log')).open('w') as out:
        rc = subprocess.call(cmd,cwd=cwd,env=env,stdout=out,stderr=subprocess.STDOUT)
    if rc:
        (a.work/'status.json').write_text(json.dumps({'status':'FAILED','stage':stage,'exit':rc})+'\n')
        raise SystemExit(rc)
    if stage == 'configure':
        config = (build/'src/hre/config.h').read_text()
        if not all('#define '+flag in config for flag in ('HAVE_SYLVAN', 'HAVE_BUDDY')):
            (a.work/'status.json').write_text(json.dumps({'status':'FAILED','stage':'required-backends-disabled'})+'\n')
            raise SystemExit('configure silently disabled a required reference backend')
audit(a.work)

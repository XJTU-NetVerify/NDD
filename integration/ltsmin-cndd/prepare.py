#!/usr/bin/env python3
"""Add the C-NDD bridge to the pinned current upstream LTSmin master."""
import argparse, hashlib, json, pathlib, shutil, subprocess
p=argparse.ArgumentParser(); p.add_argument('--base',type=pathlib.Path,required=True); p.add_argument('--out',type=pathlib.Path,required=True); p.add_argument('--mode',choices=('diagnostic','performance'),default='diagnostic'); a=p.parse_args()
here=pathlib.Path(__file__).resolve().parent; repo=here.parents[1]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
locked=json.loads((here/'upstream.json').read_text())
tree=hashlib.sha256()
for f in sorted(a.base.rglob('*')):
    if f.is_file() and '.git' not in f.relative_to(a.base).parts:
        tree.update(str(f.relative_to(a.base)).encode()+b'\0'); tree.update(f.read_bytes())
if tree.hexdigest()!=locked['source_tree_sha256']:
    raise SystemExit('Unexpected upstream source tree; use fetch-upstream.py')
if a.out.exists(): raise SystemExit('Destination already exists; use a new build directory')
shutil.copytree(a.base,a.out,symlinks=True,ignore=shutil.ignore_patterns('.git','.deps','.libs','autom4te.cache'))
def edit(name, old, new):
    path=a.out/name; s=path.read_text()
    if s.count(old)!=1: raise SystemExit('Patch context mismatch: '+name+' '+repr(old))
    path.write_text(s.replace(old,new))
edit('configure.ac','AC_CONFIG_MACRO_DIR([m4])',"""AC_ARG_VAR([CNDD_CORE_CPPFLAGS], [C-NDD public include flags])
AC_ARG_VAR([CNDD_CORE_LIBS], [Absolute C-NDD and pinned runtime static libraries])
AS_IF([test -z "$CNDD_CORE_CPPFLAGS" || test -z "$CNDD_CORE_LIBS"],
      [AC_MSG_ERROR([C-NDD requires CNDD_CORE_CPPFLAGS and CNDD_CORE_LIBS])])
AC_CONFIG_MACRO_DIR([m4])""")
edit('configure.ac','    PKG_CHECK_MODULES([SYLVAN]',
     '    AC_MSG_ERROR([C-NDD must use --without-sylvan; its engine owns the runtime])\n    PKG_CHECK_MODULES([SYLVAN]')
edit('src/vset-lib/Makefile.am','libvset_la_SOURCES  += vset_listdd64.c',"""libvset_la_SOURCES  += vset_listdd64.c
libvset_la_SOURCES  += vset_cndd.c
libvset_la_CPPFLAGS += $(CNDD_CORE_CPPFLAGS)""")
# Propagate the static runtime to every libvset consumer, including optional ones.
for path in (a.out/'src').rglob('Makefile.am'):
    s=path.read_text()
    if '../vset-lib/libvset.la' in s:
        path.write_text(s.replace('../vset-lib/libvset.la','../vset-lib/libvset.la $(CNDD_CORE_LIBS)'))
edit('src/vset-lib/vector_set.h','    VSET_SDD,','    VSET_SDD,\n    VSET_CNDD,')
edit('src/vset-lib/vector_set.c','#include <vset-lib/vdom_object.h>',
     '#include <vset-lib/vdom_object.h>\nextern struct poptOption cndd_options[];\nextern vdom_t vdom_create_cndd(int n);')
edit('src/vset-lib/vector_set.c','static si_map_entry vset_table[]={','static si_map_entry vset_table[]={\n    {"cndd", VSET_CNDD},')
edit('src/vset-lib/vector_set.c','      "select a vector set implementation from "','      "select a vector set implementation from C-NDD (cndd), "')
edit('src/vset-lib/vector_set.c','      "ldd|ldd64"','      "cndd|ldd|ldd64"')
edit('src/vset-lib/vector_set.c','    { NULL,0 , POPT_ARG_INCLUDE_TABLE , listdd_options',
     '    { NULL, 0, POPT_ARG_INCLUDE_TABLE, cndd_options, 0, "C-NDD options", NULL },\n    { NULL,0 , POPT_ARG_INCLUDE_TABLE , listdd_options')
edit('src/vset-lib/vector_set.c','    switch(impl){','    switch(impl){\n    case VSET_CNDD: return vdom_create_cndd(n);')
# Compile the ordinary symbolic frontend on Apple Silicon. This does not claim
# portability of upstream's separate explicit parallel algorithms/lock designs.
edit('src/mc-lib/atomics.h','#define mfence() { asm volatile("mfence" ::: "memory"); }',
     '#if defined(__aarch64__)\n#define mfence() __sync_synchronize()\n#else\n#define mfence() { asm volatile("mfence" ::: "memory"); }\n#endif')
edit('src/mc-lib/atomics.h','#define cpu_relax() asm volatile("pause\\n": : :"memory")',
     '#if defined(__aarch64__)\n#define cpu_relax() asm volatile("yield" ::: "memory")\n#else\n#define cpu_relax() asm volatile("pause\\n": : :"memory")\n#endif')
for src,dst in [('vset_cndd.c','src/vset-lib/vset_cndd.c'),('test-cndd.c','src/tests/test-cndd.c'),('cndd_layer_trace.h','src/pins2lts-sym/alg/cndd_layer_trace.h')]: shutil.copy2(here/src,a.out/dst)
if a.mode == 'diagnostic':
    path=a.out/'src/pins2lts-sym/alg/bfs.c'; s=path.read_text()
    needle='#include <util-lib/util.h>'; assert s.count(needle)==1
    s=s.replace(needle,needle+'\n#include "cndd_layer_trace.h"')
    needle='        stats_and_progress_report(current_level, visited, level);'
    start=s.index('\nreach_bfs_prev('); pos=s.index(needle,start)
    s=s[:pos]+'        cndd_trace_level(current_level, visited, level, N);\n'+s[pos:]; path.write_text(s)
path=a.out/'src/tests/Makefile.am'; path.write_text(path.read_text()+'''\ncheck_PROGRAMS += test-cndd
test_cndd_SOURCES = test-cndd.c
test_cndd_LDADD = $(VSET_LIB) $(PINS_LIBS) $(AM_LDFLAGS)
test_cndd_CPPFLAGS = $(AM_CPPFLAGS)
nodist_EXTRA_test_cndd_SOURCES = automake-force-linker.cxx
''')
commit=subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip()
core=hashlib.sha256()
for f in sorted(list((repo/'native/src').glob('pndd*'))+list((repo/'native/include').glob('pndd*'))): core.update(str(f.relative_to(repo)).encode()); core.update(f.read_bytes())
adapter=hashlib.sha256()
for f in sorted(here.iterdir()):
    if f.is_file(): adapter.update(f.name.encode()); adapter.update(f.read_bytes())
identity=f'ltsmin-{locked["commit"][:12]}-core-{commit[:12]}-src-{core.hexdigest()[:12]}-adapter-{adapter.hexdigest()[:12]}'
identity += '-'+a.mode
(a.out/'src/vset-lib/cndd_identity.h').write_text('#define CNDD_IDENTITY "'+identity+'"\n')
(a.out/'src/hre/git_version.h').write_text('#define GIT_VERSION "v3.1.0-dev-cndd-'+identity+'"\n')
(a.out/'cndd-provenance.json').write_text(json.dumps({'commit':commit,'core_sha256':core.hexdigest(),'adapter_sha256':adapter.hexdigest(),'identity':identity,'upstream':locked,'mode':a.mode},indent=2)+'\n')
print(identity, a.mode)

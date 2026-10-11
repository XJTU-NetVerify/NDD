#!/usr/bin/env python3
"""Adapt only the two storage primitives used by PNDD; preserve upstream notices.

Our checked label apply tasks propagate the invalid handle on table exhaustion.
Upstream recursive apply kernels cannot safely consume this sentinel and are not
used by PNDD. Never modify the source checkout supplied by the user.
"""
import shutil
import sys
from pathlib import Path

source, destination = map(Path, sys.argv[1:])
shutil.copytree(source, destination, dirs_exist_ok=True,
                ignore=shutil.ignore_patterns('.git', 'build', '__pycache__'))
p = destination / 'src/sylvan_mtbdd.c'
s = p.read_text()
old = 'if (index == 0) _mtbdd_makenode_exit();'
if s.count(old) != 1:
    raise SystemExit('Pinned Sylvan MTBDD primitive has changed')
s = s.replace(old, 'if (index == 0) return mtbdd_invalid;')
start, end = s.index('_mtbdd_makenode(uint32_t'), s.index('\nMTBDD\nmtbdd_makemapnode')
section = s[start:end].replace('if (created) sylvan_stats_count(BDD_NODES_CREATED);', 'if (created) { pndd_sylvan_node_created(); sylvan_stats_count(BDD_NODES_CREATED); }')
p.write_text('/* PNDD adaptation: checked exhaustion and allocation statistics. */\nextern void pndd_sylvan_node_created(void);\n' + s[:start] + section + s[end:])
p = destination / 'src/sylvan_zdd.c'
s = p.read_text()
start, end = s.index('_zdd_makenode(uint32_t'), s.index('\nZDD\nzdd_makemapnode')
section = s[start:end]
if section.count('exit(1);') != 1:
    raise SystemExit('Pinned Sylvan ZDD primitive has changed')
p.write_text('/* PNDD adaptation: checked exhaustion and allocation statistics. */\nextern void pndd_sylvan_node_created(void);\n' +
             s[:start] + section.replace('exit(1);', 'return zdd_invalid;').replace('if (created) sylvan_stats_count(ZDD_NODES_CREATED);', 'if (created) { pndd_sylvan_node_created(); sylvan_stats_count(ZDD_NODES_CREATED); }') + s[end:])

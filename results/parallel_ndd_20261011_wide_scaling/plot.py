#!/usr/bin/env python3
"""Plot the measured scaling curve; requires matplotlib."""
import csv
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(__file__).resolve().parent
rows = list(csv.DictReader((root / 'scaling.csv').open()))
plt.rcParams.update({'font.size': 10, 'svg.fonttype': 'none'})
fig, axes = plt.subplots(1, 2, figsize=(12, 4), sharey=True, layout='constrained')
for ax, n in zip(axes, (12, 13)):
    for backend in ('BDD', 'BCDD', 'ZDD'):
        group = sorted((r for r in rows if int(r['n']) == n and r['backend'] == backend),
                       key=lambda r: int(r['workers']))
        workers = [int(r['workers']) for r in group]
        speedup = [float(r['compute_speedup']) for r in group]
        line, = ax.plot(workers, speedup, marker='o', markersize=4, label=backend)
        ax.fill_between(workers, [float(r['paired_speedup_min']) for r in group],
                        [float(r['paired_speedup_max']) for r in group],
                        color=line.get_color(), alpha=0.08)
    ax.set(title=f'N-Queens N={n}', xlabel='Lace workers', xticks=workers)
    ax.grid(alpha=0.2)
    ax.set_ylim(0.85, 4.7)
    ax.legend(loc='lower right')
axes[0].set_ylabel('Compute speedup vs same-backend 1 worker')
fig.suptitle('Original encoding, fixed left-fold; 5 fresh-process runs per point\n'
             'Lines: ratio of medians; shading: min/max of five paired-round ratios', fontsize=11)
fig.savefig(root / 'scaling.svg')
fig.savefig(root / 'scaling.png', dpi=180)
plt.close(fig)
print('matplotlib', matplotlib.__version__)

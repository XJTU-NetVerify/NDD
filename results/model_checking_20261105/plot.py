#!/usr/bin/env python3
"""Generate runtime with a fixed-width visual BuDDy halo; preserve previous outputs."""
from pathlib import Path
import csv
import hashlib
import json
import os
import tempfile

os.environ.setdefault("MPLCONFIGDIR", tempfile.mkdtemp(prefix="runtime-clean-mpl-"))
os.environ.setdefault("XDG_CACHE_HOME", tempfile.mkdtemp(prefix="runtime-clean-fonts-"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

OUT = Path(__file__).resolve().parent
SOURCE = OUT / "observations.csv"
STEM = "reachability-buddy-order"
SUCCESS = {"PASS"}
BACKENDS = ["cndd", "sylvan", "lddmc", "fdd"]
LABEL = {"cndd": "C-NDD", "sylvan": "Sylvan BDD",
         "lddmc": "Sylvan LDD", "fdd": "BuDDy"}
COLOR = {"cndd": "#D13E32", "sylvan": "#2878B5",
         "lddmc": "#22946D", "fdd": "#777777"}
MARKER = {"cndd": "o", "sylvan": "^", "lddmc": "D", "fdd": "s"}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

assert sha(SOURCE) == "332111339d9f2f909021fdfdcca0a87cd90592710b5dba02cfaf54f79c455c70"
rows = list(csv.DictReader(SOURCE.open()))
by = {(r["model"], r["backend"]): r for r in rows}
assert len(rows) == len(by) == 240
assert all(r["phase"] == "warmup" and r["n"] == "1" for r in rows)
models = sorted({r["model"] for r in rows})
assert len(models) == 60

def runtime(m, b):
    r = by[(m, b)]
    return float(r["reachability_real_s"]) if r["status"] in SUCCESS else None

completed = sorted((m for m in models if runtime(m, "fdd") is not None),
                   key=lambda m: (runtime(m, "fdd"), m))
incomplete = sorted(m for m in models if runtime(m, "fdd") is None)
order = completed + incomplete
assert len(completed) == 54 and len(incomplete) == 6
mapping = list(csv.DictReader((OUT / "model-order.csv").open()))
assert order == [r["model"] for r in mapping]
assert [int(r["model_index"]) for r in mapping] == list(range(1, 61))
x = np.arange(1, 61)

plt.rcParams.update({
    "font.family": "DejaVu Sans", "font.size": 10,
    "axes.labelsize": 11, "axes.spines.top": False, "axes.spines.right": False,
    "svg.fonttype": "none", "pdf.fonttype": 42, "savefig.facecolor": "white",
})
fig = plt.figure(figsize=(14, 6.5))
grid = fig.add_gridspec(2, 1, height_ratios=[5, .85],
                       left=.085, right=.98, top=.9, bottom=.12, hspace=.055)
ax = fig.add_subplot(grid[0])
status = fig.add_subplot(grid[1], sharex=ax)
ax.set_yscale("log")
ax.set_ylim(.006, 900)
ax.set_xlim(.5, 60.5)
ax.set_ylabel("Reachability time (s)")
ax.grid(axis="y", which="major", color="#DFE3E8", linewidth=.65)
ax.set_axisbelow(True)
ax.tick_params(axis="x", which="both", labelbottom=False, length=0)
for a in [ax, status]:
    a.axvspan(len(completed) + .5, 60.5, color="#F0F1F3", zorder=0)
    a.axvline(len(completed) + .5, color="#B0B6BD", linewidth=.8, linestyle="--")

artists = {}
for b in ["fdd", "sylvan", "lddmc", "cndd"]:
    times = [runtime(m, b) for m in order]
    y = np.array([t if t is not None and t > 0 else np.nan for t in times])
    if b == "fdd":
        halo, = ax.plot(x, y, color=COLOR[b], linewidth=6.0, alpha=.16,
                        solid_capstyle="round", solid_joinstyle="round",
                        zorder=2, label="_nolegend_")
        assert np.array_equal(halo.get_xdata(), x)
        assert np.allclose(halo.get_ydata(), y, equal_nan=True)
    width = 1.5 if b == "cndd" else (1.2 if b == "fdd" else .85)
    line, = ax.plot(x, y, color=COLOR[b], linewidth=width, marker=MARKER[b],
                    markersize=4.3 if b == "cndd" else 3.6,
                    markeredgecolor="white", markeredgewidth=.35,
                    alpha=1 if b == "cndd" else .88, label=LABEL[b],
                    zorder=5 if b == "cndd" else 3)
    assert np.array_equal(line.get_xdata(), x)
    assert np.allclose(line.get_ydata(), y, equal_nan=True)
    artists[b] = {"linewidth": width, "positive_points": int(np.isfinite(y).sum()),
                  "points": [{"model_index": int(i), "seconds": float(t)}
                             for i, t in zip(x, y) if np.isfinite(t)]}

status.set_ylim(-.65, 3.65)
status.set_yticks(range(4), [LABEL[b] for b in BACKENDS[::-1]], fontsize=8)
status.set_xticks([1] + list(range(5, 61, 5)))
status.set_xticks(range(1, 61), minor=True)
status.tick_params(axis="x", which="minor", length=2, color="#A5ABB3")
status.tick_params(axis="y", length=0)
status.set_xlabel("Models ordered by BuDDy runtime", labelpad=8)
status.spines["left"].set_visible(False)
status.spines["bottom"].set_color("#BFC6CE")
marks = []
for y, b in enumerate(BACKENDS[::-1]):
    status.axhline(y, color="#E5E8EB", linewidth=.45, zorder=0)
    for i, m in enumerate(order, 1):
        r = by[(m, b)]
        t = runtime(m, b)
        text = ("TO" if r["status"] == "TIMEOUT" else
                "ERR" if r["status"] not in SUCCESS else
                "0.000 s" if t == 0 else None)
        if text:
            status.text(i, y, text, ha="left" if text == "0.000 s" else "center", va="center",
                        fontsize=6.7, weight="bold", color=COLOR[b])
            marks.append({"model_index": i, "model": m, "backend": b, "mark": text})
assert sum(a["positive_points"] for a in artists.values()) + len(marks) == 240
handles, labels = ax.get_legend_handles_labels()
indices = [labels.index(LABEL[b]) for b in BACKENDS]
fig.legend([handles[i] for i in indices], [labels[i] for i in indices],
           loc="upper center", bbox_to_anchor=(.53, .985),
           ncol=4, frameon=False, columnspacing=2.6, handlelength=2.8)
fig.text(.53, .92, "Warmup n=1 per cell; 4 accidental-invalid records replaced by one authorized retest each", ha="center", fontsize=8, color="#59677c")
fig.canvas.draw()
for ext in ["png", "svg", "pdf"]:
    fig.savefig(OUT / f"{STEM}.{ext}", dpi=200)
plt.close(fig)
# Normalize generated SVG whitespace for a clean textual Git diff.
svg_path = OUT / f"{STEM}.svg"
svg_path.write_text("\n".join(line.rstrip() for line in svg_path.read_text().splitlines()) + "\n")

audit = {
    "metric": "reported reachability real seconds; logarithmic axis",
    "display_labels": LABEL,
    "source_sha256": sha(SOURCE),
    "model_order_sha256": sha(OUT / "model-order.csv"),
    "models": 60, "observations": 240, "order_unchanged": True,
    "buddy_completed": len(completed), "buddy_incomplete_models": incomplete,
    "artists": artists, "status_marks": marks,
    "design": "Final BuDDy halo plot with explicit four-retest warmup subtitle",
    "buddy_halo": {"linewidth_pt": 6.0, "alpha": .16, "zorder": 2,
                   "meaning": "visual emphasis only; not uncertainty or confidence interval",
                   "same_nan_breaks_as_buddy": True},
    "source_style": "Final BuDDy-sorted runtime halo; label MTPNDD replaced by C-NDD",
    "no_epsilon_or_timeout_substitution": True,
    "benchmark_runs": 0,
    "sha256": {ext: sha(OUT / f"{STEM}.{ext}") for ext in ["png", "svg", "pdf"]},
}
(OUT / "runtime-halo-audit.json").write_text(json.dumps(audit, indent=2) + "\n")
print(json.dumps({k: v for k, v in audit.items() if k not in {"artists", "status_marks"}}, indent=2))

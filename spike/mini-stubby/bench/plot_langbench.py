#!/usr/bin/env python3
"""Plot Java-vs-Kotlin full-compile time vs file size (on-device A56)."""
import csv, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

rows = list(csv.DictReader(open(sys.argv[1] if len(sys.argv) > 1 else "langbench-ondevice.csv")))
d = {"kotlin": [], "java": []}
for r in rows:
    d[r["lang"]].append((int(r["loc"]), int(r["median_ms"]), int(r["min_ms"])))
for k in d: d[k].sort()

fig, ax = plt.subplots(figsize=(9, 5.6), dpi=140)
styles = {"kotlin": ("#7F52FF", "o", "Kotlin  (K2JVMCompiler, full compile)"),
          "java":   ("#E76F00", "s", "Java  (javac, full compile)")}
for lang, (color, mk, label) in styles.items():
    xs = [p[0] for p in d[lang]]; med = [p[1] for p in d[lang]]; mn = [p[2] for p in d[lang]]
    ax.plot(xs, med, color=color, marker=mk, lw=2.2, ms=7, label=label, zorder=3)
    ax.fill_between(xs, mn, med, color=color, alpha=0.12, zorder=1)
    for x, y in zip(xs, med):
        ax.annotate(f"{y}", (x, y), textcoords="offset points", xytext=(0, 8),
                    ha="center", fontsize=7.5, color=color)

ax.set_xlabel("Source file size (lines of real code)")
ax.set_ylabel("Warm full-compile time (ms, median of 5)")
ax.set_title("On-device compile time by file size — Java vs Kotlin\n"
             "Samsung A56 (Exynos 1580), CoGo bundled JDK 21, in-process & warm",
             fontsize=11)
ax.grid(True, ls=":", alpha=0.5)
ax.set_ylim(0, None); ax.set_xlim(0, 1200)
ax.legend(loc="upper left", frameon=True, fontsize=9)
ax.text(0.99, 0.03,
        "Full compile from scratch (not the incremental fast-loop, which is ~flat with size).\n"
        "Shaded band = min→median. javac stays ~120–190 ms at any size; kotlinc grows 0.6→1.3 s.",
        transform=ax.transAxes, ha="right", va="bottom", fontsize=7.5, color="#555")
fig.tight_layout()
out = "langbench-ondevice.png"
fig.savefig(out)
print("wrote", out)

#!/usr/bin/env python3
"""Plot Java-vs-Kotlin full-compile time vs file size (on-device A56) with Seaborn."""
import sys
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt

csv = sys.argv[1] if len(sys.argv) > 1 else "langbench-ondevice.csv"
df = pd.read_csv(csv)
df["lang"] = df["lang"].map({"kotlin": "Kotlin (K2JVMCompiler)", "java": "Java (javac)"})

sns.set_theme(style="whitegrid", context="talk")
palette = {"Kotlin (K2JVMCompiler)": "#7F52FF", "Java (javac)": "#E76F00"}

fig, ax = plt.subplots(figsize=(9.5, 6), dpi=140)
# min→median band per language
for lang, sub in df.groupby("lang"):
    sub = sub.sort_values("loc")
    ax.fill_between(sub["loc"], sub["min_ms"], sub["median_ms"],
                    color=palette[lang], alpha=0.12, zorder=1)
sns.lineplot(data=df, x="loc", y="median_ms", hue="lang", style="lang",
             markers=True, dashes=False, palette=palette, linewidth=2.4,
             markersize=9, ax=ax, zorder=3)

for _, r in df.iterrows():
    ax.annotate(f"{int(r['median_ms'])}", (r["loc"], r["median_ms"]),
                textcoords="offset points", xytext=(0, 10), ha="center",
                fontsize=9, color=palette[r["lang"]])

ax.set_xlabel("Source file size (lines of real code)")
ax.set_ylabel("Warm full-compile time (ms, median of 5)")
ax.set_title("On-device compile time by file size — Java vs Kotlin", fontsize=15, pad=26)
ax.text(0.5, 1.02, "Samsung A56 (Exynos 1580) · CoGo bundled JDK 21 · in-process & warm",
        transform=ax.transAxes, ha="center", va="bottom", fontsize=10, color="#666")
ax.set_ylim(0, None); ax.set_xlim(0, 1200)
ax.legend(title="", loc="upper left", frameon=True, fontsize=11)
ax.text(0.99, 0.03,
        "Full compile from scratch (not the incremental fast-loop, which is ~flat with size).\n"
        "Shaded band = min→median. javac stays ~120–190 ms at any size; kotlinc grows 0.6→1.3 s.",
        transform=ax.transAxes, ha="right", va="bottom", fontsize=9, color="#555")
sns.despine(left=True, bottom=True)
fig.tight_layout()
out = "langbench-ondevice.png"
fig.savefig(out, bbox_inches="tight")
print("wrote", out)

"""ADFA-5156 / R8 plugin-impact analysis. See README.md in this directory.

Plugins load parent-first through a stock DexClassLoader, so every kotlin.**
class a plugin references resolves from the IDE's dex, not from the stdlib the
plugin bundles. R8 cannot see plugin call sites, so it strips every stdlib
member the IDE itself does not call, and plugins die with NoSuchMethodError at
runtime. This tool simulates that resolution so the breakage can be measured
from a build artifact instead of discovered on a device.

Run this before shipping any release build that re-enables R8 shrinking.

uv run --no-project analyze-plugin-impact.py impact <ref-dump> <cand-dump> <plugindir>
uv run --no-project analyze-plugin-impact.py explain-method <dump> <class> <method>
uv run --no-project analyze-plugin-impact.py explain-absent <dump> <plugindir>

Dumps come from dex-dump.sh. IMPORTANT: read the "Known false positives"
section of README.md before acting on any NoSuchMethod result -- methods
inherited from the Android boot classpath are reported as missing because
java.util.* is not in the APK.
"""

import re
import sys
from collections import defaultdict
from pathlib import Path

CLS_RE = re.compile(r"^  Class descriptor  : '([^']+)'")
SUPER_RE = re.compile(r"^  Superclass        : '([^']+)'")
IFACE_RE = re.compile(r"^    #\d+              : '([^']+)'")
NAME_RE = re.compile(r"^      name          : '([^']*)'")
TYPE_RE = re.compile(r"^      type          : '([^']*)'")
SECTION_RE = re.compile(
	r"^  (Direct methods|Virtual methods|Static fields|Instance fields|Interfaces)"
)
INVOKE_RE = re.compile(r"invoke-[a-z/-]+ \{[^}]*\}, (L[^;]+;)\.([^:]+):(\([^)]*\)\S*)")

STDLIB_PREFIXES = ("Lkotlin/", "Lkotlinx/")

OK = "OK"
NO_METHOD = "NO_METHOD"
CLASS_ABSENT = "CLASS_ABSENT"


def parse_host(dump_path):
	"""Index a host (IDE) dex dump.

	Returns {class: {'super': str|None, 'ifaces': [str], 'methods': {'name:sig'}}}
	"""
	idx = {}
	cur = None
	in_ifaces = False
	pending = None
	with open(dump_path, "r", errors="replace") as fh:
		for line in fh:
			m = CLS_RE.match(line)
			if m:
				cur = {"super": None, "ifaces": [], "methods": set()}
				idx[m.group(1)] = cur
				in_ifaces = False
				pending = None
				continue
			if cur is None:
				continue
			m = SUPER_RE.match(line)
			if m:
				cur["super"] = m.group(1)
				continue
			m = SECTION_RE.match(line)
			if m:
				# Interface entries look like method index lines, so track the
				# section to tell them apart.
				in_ifaces = m.group(1) == "Interfaces"
				continue
			if in_ifaces:
				m = IFACE_RE.match(line)
				if m:
					cur["ifaces"].append(m.group(1))
				continue
			m = NAME_RE.match(line)
			if m:
				pending = m.group(1)
				continue
			m = TYPE_RE.match(line)
			if m and pending is not None:
				cur["methods"].add(pending + ":" + m.group(1))
				pending = None
	return idx


def resolve(idx, cls, name, sig):
	"""Walk cls -> superclasses -> interfaces looking for name:sig.

	Returns OK, NO_METHOD, or CLASS_ABSENT. NO_METHOD can be a false positive
	when the real declaration lives on the Android boot classpath; see README.
	"""
	if cls not in idx:
		return CLASS_ABSENT
	key = name + ":" + sig
	seen, stack = set(), [cls]
	while stack:
		c = stack.pop()
		if c in seen or c not in idx:
			continue
		seen.add(c)
		e = idx[c]
		if key in e["methods"]:
			return OK
		if e["super"]:
			stack.append(e["super"])
		stack.extend(e["ifaces"])
	return NO_METHOD


def parse_plugin(dis_path):
	"""Call sites into kotlin/kotlinx FROM a plugin's own classes.

	Calls originating inside the plugin's bundled stdlib copy are excluded --
	that copy is shadowed by the host at runtime, so they are not real call
	sites. Returns {(cls, name, sig): count}.
	"""
	sites = defaultdict(int)
	cur = None
	with open(dis_path, "r", errors="replace") as fh:
		for line in fh:
			m = CLS_RE.match(line)
			if m:
				cur = m.group(1)
				continue
			if cur is None or cur.startswith(STDLIB_PREFIXES):
				continue
			m = INVOKE_RE.search(line)
			if m and m.group(1).startswith(STDLIB_PREFIXES):
				sites[(m.group(1), m.group(2), m.group(3))] += 1
	return sites


def plugin_dumps(plugindir):
	for d in sorted(Path(plugindir).iterdir()):
		if not d.is_dir():
			continue
		dis = d / "dis.txt"
		if dis.exists():
			yield d.name, dis


def cmd_impact(ref_dump, cand_dump, plugindir):
	sys.stderr.write("indexing reference host dex...\n")
	ref = parse_host(ref_dump)
	sys.stderr.write("indexing candidate host dex...\n")
	cand = parse_host(cand_dump)
	sys.stderr.write(f"reference classes={len(ref)}  candidate classes={len(cand)}\n")

	rows, regressions, remaining = [], [], defaultdict(set)
	for name, dis in plugin_dumps(plugindir):
		sites = parse_plugin(dis)
		cr, cc = defaultdict(int), defaultdict(int)
		for (c, n, s) in sites:
			vr, vc = resolve(ref, c, n, s), resolve(cand, c, n, s)
			cr[vr] += 1
			cc[vc] += 1
			if vr == OK and vc != OK:
				regressions.append((name, f"{c}.{n}{s}", vr, vc))
			if vc == NO_METHOD:
				remaining[name].add(f"{c}.{n}{s}")
		rows.append((name, len(sites), cr, cc))

	w = 106
	print(f"\n{'plugin':<26} {'sites':>6} | {'REFERENCE':^28} | {'CANDIDATE':^28}")
	print(f"{'':<26} {'':>6} | {'ok':>6} {'NoSuchMethod':>13} {'absent':>7} |"
		f" {'ok':>6} {'NoSuchMethod':>13} {'absent':>7}")
	print("-" * w)
	tr, tc = defaultdict(int), defaultdict(int)
	for name, n, cr, cc in rows:
		for k in (OK, NO_METHOD, CLASS_ABSENT):
			tr[k] += cr[k]
			tc[k] += cc[k]
		print(f"{name:<26} {n:>6} | {cr[OK]:>6} {cr[NO_METHOD]:>13} {cr[CLASS_ABSENT]:>7} |"
			f" {cc[OK]:>6} {cc[NO_METHOD]:>13} {cc[CLASS_ABSENT]:>7}")
	print("-" * w)
	print(f"{'TOTAL':<26} {sum(r[1] for r in rows):>6} |"
		f" {tr[OK]:>6} {tr[NO_METHOD]:>13} {tr[CLASS_ABSENT]:>7} |"
		f" {tc[OK]:>6} {tc[NO_METHOD]:>13} {tc[CLASS_ABSENT]:>7}")

	print("\n=== REGRESSIONS (resolved in reference, broken in candidate) ===")
	if regressions:
		for name, sitename, a, b in regressions:
			print(f"  {name}: {sitename}  {a} -> {b}")
	else:
		print("  none")

	print("\n=== NoSuchMethod in candidate ===")
	print("  Check each against README 'Known false positives' -- boot-classpath")
	print("  inheritance (java.util.*) is reported here but is not a real failure.")
	print("  Use: analyze-plugin-impact.py explain-method <cand-dump> <class> <method>")
	if remaining:
		for name in sorted(remaining):
			print(f"  {name}:")
			for site in sorted(remaining[name]):
				print(f"      {site}")
	else:
		print("  none")

	return 1 if regressions else 0


def cmd_explain_method(dump, cls, method):
	"""Trace the resolution chain, showing where it leaves the APK."""
	idx = parse_host(dump)
	if cls not in idx:
		print(f"{cls}: ABSENT from this dex")
		return 0
	print(f"{cls}.{method}")
	seen, stack, outside = set(), [cls], False
	while stack:
		c = stack.pop()
		if c in seen:
			continue
		seen.add(c)
		if c in idx:
			e = idx[c]
			decl = any(m.split(":")[0] == method for m in e["methods"])
			print(f"   in-apk  {c}{'   <-- declares ' + method if decl else ''}")
			if e["super"]:
				stack.append(e["super"])
			stack.extend(e["ifaces"])
		else:
			outside = True
			print(f"   OUTSIDE APK (boot classpath) {c}")
	if outside:
		print("\n   Chain exits the APK. If the method is declared on a java.* type,"
			"\n   this is a FALSE POSITIVE -- see README 'Known false positives'.")
	return 0


def cmd_explain_absent(dump, plugindir):
	"""List CLASS_ABSENT fall-throughs and flag the split-brain shape."""
	idx = parse_host(dump)
	absent = defaultdict(set)
	for name, dis in plugin_dumps(plugindir):
		for (c, n, s) in parse_plugin(dis):
			if resolve(idx, c, n, s) == CLASS_ABSENT:
				absent[name].add(c)

	allcls = set()
	print("Fall-through classes (absent from the host dex, so the plugin's own copy loads):\n")
	for p, cs in sorted(absent.items()):
		print(f"  {p}  ({len(cs)} distinct)")
		for c in sorted(cs):
			print(f"      {c}")
		allcls |= cs

	print("\nPackage rollup:")
	pkg = defaultdict(int)
	for c in allcls:
		pkg[c.rsplit("/", 1)[0]] += 1
	for p, n in sorted(pkg.items(), key=lambda kv: -kv[1]):
		print(f"  {n:>3}  {p}")

	print("\nClasses in packages the host DOES ship (inspect these -- a class absent")
	print("while its supertype is present-and-stripped is the ADFA-5156 shape):")
	flagged = False
	for c in sorted(allcls):
		pkgname = c.rsplit("/", 1)[0]
		n = sum(1 for k in idx if k.rsplit("/", 1)[0] == pkgname)
		if n:
			flagged = True
			synth = "$$ExternalSynthetic" in c or c.endswith("$DefaultImpls;")
			note = "  (D8/Kotlin build-time synthetic, expected)" if synth else ""
			print(f"      {c}   host has {n} others in that package{note}")
	if not flagged:
		print("      none -- every absent class is in a package the host does not ship")
	return 0


USAGE = """usage:
analyze-plugin-impact.py impact <ref-dump> <cand-dump> <plugindir>
analyze-plugin-impact.py explain-method <dump> <class-descriptor> <method>
analyze-plugin-impact.py explain-absent <dump> <plugindir>

<class-descriptor> is dex form, e.g. Lkotlin/collections/AbstractMutableSet;
"""


def main():
	if len(sys.argv) < 2:
		print(USAGE, file=sys.stderr)
		return 2
	cmd, rest = sys.argv[1], sys.argv[2:]
	handlers = {
		"impact": (3, cmd_impact),
		"explain-method": (3, cmd_explain_method),
		"explain-absent": (2, cmd_explain_absent),
	}
	if cmd not in handlers or len(rest) != handlers[cmd][0]:
		print(USAGE, file=sys.stderr)
		return 2
	return handlers[cmd][1](*rest)


if __name__ == "__main__":
	sys.exit(main())

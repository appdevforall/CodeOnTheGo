#!/usr/bin/env python3
"""Runs every ```sql example in pages/*.md against a real sqlite3 with sqlite-js
loaded, and checks that internal links resolve.

The examples of a page run in order in one session, as a reader follows them: an
example may use what the ones before it made. The `-- text` comment lines in a
block are the output it must end with. A block with no such lines only has to
succeed.

Usage: SQLITE3=sqlite3 EXT=/path/to/sqlite-js.so python3 test_examples.py
Prints TAP. Exits 0 only if every example passes.
"""
import glob
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SQLITE3 = os.environ.get("SQLITE3", "sqlite3")
EXT = os.environ.get("EXT", "sqlite-js")
# Pages of the existing help that the new pages link to.
EXISTING = {"terminal-top.html", "cogo-top.html"}


def main():
    n = failed = 0
    files = sorted(glob.glob(os.path.join(HERE, "pages", "*.md")))
    known = {re.search(r"^file: (.*)$", open(f, encoding="utf-8").read(), re.M).group(1) for f in files} | EXISTING
    for path in files:
        text = open(path, encoding="utf-8").read()
        name = os.path.basename(path)
        earlier = []
        for k, block in enumerate(re.findall(r"^```sql\n(.*?)^```", text, re.M | re.S), 1):
            n += 1
            want = [m.group(1) for m in re.finditer(r"^-- ?(.*)$", block, re.M)]
            sql = "\n".join(l for l in block.split("\n") if not l.startswith("--"))
            earlier.append(sql)
            all_sql = "\n".join(earlier)
            r = subprocess.run([SQLITE3, "-batch", "-noheader", "-cmd", '.load "%s"' % EXT, ":memory:", all_sql],
                               capture_output=True, text=True, timeout=60)
            got = r.stdout.rstrip("\n").split("\n") if r.stdout.strip() else []
            ok = r.returncode == 0 and (not want or got[-len(want):] == want)
            print("%s %d - %s example %d" % ("ok" if ok else "not ok", n, name, k))
            if not ok:
                failed += 1
                print("#   exit %d, want %r, got %r, stderr %r" % (r.returncode, want, got[-len(want) or 1:], r.stderr[:200]))
        for url in re.findall(r"\]\(([^)]+)\)", text):
            if not re.match(r"https?://", url):
                n += 1
                ok = url in known
                print("%s %d - %s link %s" % ("ok" if ok else "not ok", n, name, url))
                failed += not ok
    print("1..%d" % n)
    print("# FAILED %d of %d" % (failed, n) if failed else "# passed all %d" % n)
    sys.exit(1 if failed else 0)


main()

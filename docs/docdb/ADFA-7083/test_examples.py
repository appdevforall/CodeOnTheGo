#!/usr/bin/env python3
"""Runs every ```sql example in pages/*.md against a real sqlite3 with sqlite-js
loaded, and checks that internal links, including each page's parent, prev and
next, resolve.

The examples of a page run in order in one session, as a reader follows them: an
example may use what the ones before it made. The `-- text` comment lines in a
block are the output it must end with. A block with no such lines only has to
succeed. An example that creates nothing, no table, row or function, also runs a
second time in the same session, as a reader trying it again would, and must
give the same output.

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
# An example that makes something cannot simply run again: a second CREATE TABLE fails.
MAKES_SOMETHING = re.compile(r"\b(CREATE|INSERT|js_create_\w+|js_init_table)\b", re.I)
# Printed just before the run under test, so that only its own output is compared.
BOUNDARY = "test_examples: run under test"


def run(sql):
    r = subprocess.run([SQLITE3, "-batch", "-noheader", "-cmd", '.load "%s"' % EXT, ":memory:", sql],
                       capture_output=True, text=True, timeout=60)
    return r, (r.stdout.rstrip("\n").split("\n") if r.stdout.strip() else [])


def main():
    n = failed = 0
    files = sorted(glob.glob(os.path.join(HERE, "pages", "*.md")))
    known = {re.search(r"^file: (.*)$", open(f, encoding="utf-8").read(), re.M).group(1) for f in files} | EXISTING
    for path in files:
        text = open(path, encoding="utf-8").read()
        name = os.path.basename(path)
        earlier = []
        for k, block in enumerate(re.findall(r"^```sql\n(.*?)^```", text, re.M | re.S), 1):
            want = [m.group(1) for m in re.finditer(r"^-- ?(.*)$", block, re.M)]
            sql = "\n".join(l for l in block.split("\n") if not l.startswith("--"))
            mark = "SELECT '%s';" % BOUNDARY
            runs = [("", "\n".join(earlier + [mark, sql]))]
            if not MAKES_SOMETHING.search(sql):
                runs.append((" run again", "\n".join(earlier + [sql, mark, sql])))
            earlier.append(sql)
            for label, all_sql in runs:
                n += 1
                r, out = run(all_sql)
                got = out[len(out) - out[::-1].index(BOUNDARY):] if BOUNDARY in out else []
                ok = r.returncode == 0 and BOUNDARY in out and (not want or got[-len(want):] == want)
                print("%s %d - %s example %d%s" % ("ok" if ok else "not ok", n, name, k, label))
                if not ok:
                    failed += 1
                    print("#   exit %d, want %r, got %r, stderr %r"
                          % (r.returncode, want, got[-(len(want) or 1):], r.stderr[:200]))
        front = re.match(r"---\n(.*?)\n---\n", text, re.S).group(1)
        for key, url in re.findall(r"^(parent|prev|next): (.*)$", front, re.M):
            n += 1
            ok = url in known
            print("%s %d - %s %s %s" % ("ok" if ok else "not ok", n, name, key, url))
            failed += not ok
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

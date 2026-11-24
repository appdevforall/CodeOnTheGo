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

A `-- Error: text` line says that the statement above it fails, with a message
that contains the text. A session must report exactly the errors its examples
name, in order, each at the line where that statement starts, and no others.
sqlite3 reads the examples from its standard input, where, as when a reader
types them, it goes on after an error.

The ```kotlin, ```java, ```gradle and ```gradle-java examples of the app pages are
checked by test_app_examples.py.

Usage: SQLITE3=sqlite3 EXT=/path/to/sqlite-js.so python3 test_examples.py
Prints TAP. Exits 0 only if every example passes.
"""
import glob
import os
import re
import sqlite3
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SQLITE3 = os.environ.get("SQLITE3", "sqlite3")
EXT = os.environ.get("EXT", "sqlite-js")
# Pages of the existing help that the new pages link to.
EXISTING = {"terminal-top.html", "cogo-top.html", "project-create-new.html"}
# An example that makes something cannot simply run again: a second CREATE TABLE fails.
MAKES_SOMETHING = re.compile(r"\b(CREATE|INSERT|PRAGMA|js_create_\w+|js_init_table)\b", re.I)
# Printed just before the run under test, so that only its own output is compared.
BOUNDARY = "test_examples: run under test"


# What sqlite3 prints on standard error for a failed statement, across its versions:
# "Runtime error near line 3: ...", "Parse error near line 3: ..." or "Error: near line 3: ...".
ERROR_LINE = re.compile(r"^(Runtime error|Parse error|Error)\b")
# The line of its standard input where the failed input starts, in each of those forms.
NEAR_LINE = re.compile(r"near line (\d+):")


def run(sql):
    """Runs sql in one session; returns the lines it printed, the errors it reported, its
    standard error and its exit code."""
    # -init: none of the settings of the user's ~/.sqliterc, such as .bail on
    r = subprocess.run([SQLITE3, "-batch", "-noheader", "-init", os.devnull, "-cmd", '.load "%s"' % EXT, ":memory:"],
                       input=sql + "\n", capture_output=True, text=True, timeout=60)
    out = r.stdout.rstrip("\n").split("\n") if r.stdout.strip() else []
    return out, [l for l in r.stderr.split("\n") if ERROR_LINE.match(l)], r.stderr, r.returncode


HAS_DARK, ENDING_SEMI = 1, 2
BLANK = " \t\n\v\f\r"


def quickscan(line, state):
    """sqlite3's shell quickscan() (3.46.1, src/shell.c.in): scans one line, resuming from state,
    and returns the new state. A state is (flags, wait): HAS_DARK once the input has had anything
    but blanks, comments and ';'; ENDING_SEMI while its last such thing is a ';'; and wait, the
    character that ends the quote or /* comment the line ends inside, or ""."""
    flags, wait = state
    i, n = 0, len(line)
    while i < n:
        c = line[i]
        i += 1
        if wait == "*":
            if c == "*" and line[i:i + 1] == "/":
                i += 1
                wait = ""
        elif wait:
            if c == wait:
                if c != "]" and line[i:i + 1] == c:
                    i += 1  # a doubled quote
                else:
                    wait = ""
        elif c in BLANK:
            pass
        elif c == "-" and line[i:i + 1] == "-":
            break  # the rest of the line is a comment
        elif c == ";":
            flags |= ENDING_SEMI
        elif c == "/" and line[i:i + 1] == "*":
            i += 1
            wait = "*"
        elif c in "[`'\"":
            wait = "]" if c == "[" else c
            flags = HAS_DARK
        else:
            flags = (flags & ~ENDING_SEMI) | HAS_DARK
    return flags, wait


def split(block):
    """An example's SQL; the output lines it must end with; and the errors it must report, each
    as its text and the line of the SQL, from 0, where the statement above its note starts.

    sqlite3 reports an error at the line where the input it was running starts. Its shell
    (process_input() in 3.46.1) swallows a line that holds only blanks and comments, as
    quickscan() reads it, and a dot command or a '#' line, when no input is pending; and drops
    pending input that has become only blanks and comments, such as a /* */ comment over several
    lines. A statement therefore starts at the first line after the last complete statement that
    is none of these. Not modelled: a line '/' or 'go' that ends a statement, and a statement left
    unfinished at the end of an example, which sqlite3 runs at the end of its input. Either makes
    the check fail, never pass."""
    lines, want, errors = [], [], []
    pending, start, last, state = "", None, None, (0, "")
    for line in block.split("\n"):
        if line.startswith("--"):
            note = re.match(r"^-- ?(.*)$", line).group(1)
            if note.startswith("Error: "):
                if last is None:
                    raise ValueError("%r comes before any complete statement" % line)
                errors.append((note[len("Error: "):], last))
            else:
                want.append(note)
            continue
        lines.append(line)
        state = quickscan(line, state)
        flags, wait = state
        plain_white = not wait and not flags & HAS_DARK
        if not pending:
            if plain_white:
                state = (0, "")
                continue
            if line[:1] in (".", "#"):
                if line.startswith("."):
                    last = len(lines) - 1
                state = (0, "")
                continue
            start = len(lines) - 1
        pending += line + "\n"
        if not wait and flags & ENDING_SEMI and sqlite3.complete_statement(pending):
            last, pending, start, state = start, "", None, (0, "")
        elif plain_white:
            pending, start, state = "", None, (0, "")
    return "\n".join(lines), want, errors


def at(errors, first):
    """The errors, each with the line of standard input, from 1, where its statement starts, for
    SQL whose first line is line first."""
    return [(text, first + line) for text, line in errors]


def lines(sql):
    return sql.count("\n") + 1


def main():
    n = failed = 0
    files = sorted(glob.glob(os.path.join(HERE, "pages", "*.md")))
    known = {re.search(r"^file: (.*)$", open(f, encoding="utf-8").read(), re.M).group(1) for f in files} | EXISTING
    for path in files:
        text = open(path, encoding="utf-8").read()
        name = os.path.basename(path)
        earlier, earlier_errors = [], []
        for k, block in enumerate(re.findall(r"^```sql\n(.*?)^```", text, re.M | re.S), 1):
            try:
                sql, want, errors = split(block)
            except ValueError as e:
                n += 1
                failed += 1
                print("not ok %d - %s example %d" % (n, name, k))
                print("#   %s" % e)
                continue
            mark = "SELECT '%s';" % BOUNDARY
            # the line of standard input, from 1, where this example starts in each run
            before = sum(lines(e) for e in earlier)
            runs = [("", "\n".join(earlier + [mark, sql]), earlier_errors + at(errors, before + 2))]
            if not MAKES_SOMETHING.search(sql):
                runs.append((" run again", "\n".join(earlier + [sql, mark, sql]),
                             earlier_errors + at(errors, before + 1) + at(errors, before + lines(sql) + 2)))
            earlier.append(sql)
            earlier_errors += at(errors, before + 1)
            for label, all_sql, want_errors in runs:
                n += 1
                out, got_errors, stderr, code = run(all_sql)
                got = out[len(out) - out[::-1].index(BOUNDARY):] if BOUNDARY in out else []
                # sqlite3 exits with 1 after a session that reported an error and with 0 after one
                # that reported none: anything else is a crash
                ok = (code == (1 if want_errors else 0)
                      and BOUNDARY in out and (not want or got[-len(want):] == want)
                      and len(got_errors) == len(want_errors)
                      and all(w in g and NEAR_LINE.search(g) and int(NEAR_LINE.search(g).group(1)) == n
                              for (w, n), g in zip(want_errors, got_errors)))
                print("%s %d - %s example %d%s" % ("ok" if ok else "not ok", n, name, k, label))
                if not ok:
                    failed += 1
                    print("#   exit %d, want %r, got %r, want errors %r, stderr %r"
                          % (code, want, got[-(len(want) or 1):], want_errors, stderr[:300]))
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

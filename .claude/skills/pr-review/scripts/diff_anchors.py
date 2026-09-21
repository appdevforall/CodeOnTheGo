"""Map a unified diff to the (path, side, line) anchors GitHub will accept.

GitHub rejects an inline PR comment whose line is not part of the diff, so every
finding has to be checked against the hunks before it is posted. This also
recomputes right-side line numbers, which a reviewer reading the diff by eye
gets wrong often enough to matter.

Usage:
    gh pr diff <n> --repo <owner/repo> > /tmp/pr.diff

    python diff_anchors.py /tmp/pr.diff --file Foo.kt
        Print every postable anchor in files whose path ends with "Foo.kt",
        as "SIDE line: text", so the text can be eyeballed against the claim.

    python diff_anchors.py /tmp/pr.diff --check path/to/Foo.kt:84
        Exit 0 and print the line's text if that anchor is postable on RIGHT,
        exit 1 otherwise. Append :LEFT to check the deleted side.

    python diff_anchors.py /tmp/pr.diff --json
        Emit {path: {"RIGHT": {line: text}, "LEFT": {line: text}}} for scripting.
"""

import argparse
import json
import re
import sys

HUNK = re.compile(r"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def parse(diff_text):
    """Return {path: {"RIGHT": {line: text}, "LEFT": {line: text}}} for one diff."""
    files = {}
    path = None
    left = right = 0
    for raw in diff_text.split("\n"):
        if raw.startswith("+++ "):
            target = raw[4:].strip()
            path = None if target == "/dev/null" else target[2:] if target.startswith("b/") else target
            if path:
                files.setdefault(path, {"RIGHT": {}, "LEFT": {}})
            continue
        if raw.startswith("--- ") or raw.startswith("diff ") or raw.startswith("index "):
            continue
        hunk = HUNK.match(raw)
        if hunk:
            left, right = int(hunk.group(1)), int(hunk.group(2))
            continue
        if path is None or not raw:
            continue
        if raw.startswith("\\"):
            continue
        text = raw[1:]
        if raw.startswith("+"):
            files[path]["RIGHT"][right] = text
            right += 1
        elif raw.startswith("-"):
            files[path]["LEFT"][left] = text
            left += 1
        elif raw.startswith(" "):
            files[path]["RIGHT"][right] = text
            files[path]["LEFT"][left] = text
            right += 1
            left += 1
    return files


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("diff", help="path to a unified diff, or - for stdin")
    ap.add_argument("--file", help="only show paths ending with this")
    ap.add_argument("--check", help="PATH:LINE[:SIDE] - exit 0 if postable")
    ap.add_argument("--json", action="store_true", help="emit the full anchor map")
    args = ap.parse_args()

    text = sys.stdin.read() if args.diff == "-" else open(args.diff, encoding="utf-8", errors="replace").read()
    files = parse(text)

    if args.check:
        parts = args.check.split(":")
        if len(parts) < 2:
            sys.exit("--check wants PATH:LINE[:SIDE]")
        want_path, want_line = parts[0], int(parts[1])
        side = parts[2].upper() if len(parts) > 2 else "RIGHT"
        for path, sides in files.items():
            if path.endswith(want_path) and want_line in sides.get(side, {}):
                print("{} {} {}: {}".format(path, side, want_line, sides[side][want_line]))
                return
        print("not postable: {} {} {}".format(want_path, side, want_line), file=sys.stderr)
        sys.exit(1)

    if args.json:
        json.dump(files, sys.stdout, indent=1, sort_keys=True)
        print()
        return

    for path, sides in sorted(files.items()):
        if args.file and not path.endswith(args.file):
            continue
        print("== {}".format(path))
        for side in ("RIGHT", "LEFT"):
            for line in sorted(sides[side]):
                print("{:>5} {:>4}: {}".format(side, line, sides[side][line]))


if __name__ == "__main__":
    main()

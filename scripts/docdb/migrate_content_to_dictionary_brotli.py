#!/usr/bin/env python3
"""Recompress documentation.db's Brotli Content rows against the shared dictionary.

Reads the dictionary from the database's own CompressionDictionary table (id = 1)
and rewrites every `ContentTypes.compression = 'brotli'` row so it is compressed
against that dictionary instead of plainly. WebServer tries a dictionary-attached
decode first and falls back to a plain one, so a half-migrated database still
serves -- which is what makes running this incrementally safe.

Two properties of the data shape this script, both verified against the 20-Aug
database rather than assumed:

  * Content over 1 MiB is *not* stored as independently compressed pieces. The
    rows are raw 1 MiB slices of one Brotli stream: `path`, then `path-N`
    continuations. A slice on its own does not decode. So the unit of work here
    is a logical item -- a base row plus its continuations -- concatenated,
    decoded, recompressed, and re-split. Migrating such rows one at a time would
    destroy the content.

  * A few rows decode identically with and without the dictionary: tiny,
    already-compressed payloads where the compressor found nothing to reference.
    Those are left alone, so "already migrated" covers them as well as genuinely
    dictionary-bound rows, and re-running does not churn them.

  * The continuation rows in that database are numbered from **-2**, while
    WebServer's reassembly loop starts at -1 (ADFA-5170), so those items already
    serve truncated. This script preserves whatever numbering it finds, keeping
    the migration behaviour-neutral; --renumber-continuations rewrites them from
    -1 instead, which incidentally makes them reachable again.

Parallel by default: compression at quality 11 is the whole cost (~4 GB of
plaintext), and it parallelises perfectly across cores.

Usage:
    # inspect: what would change, nothing written
    migrate_content_to_dictionary_brotli.py documentation.db --dry-run

    # migrate a copy, then swap it in
    cp documentation.db migrated.db
    migrate_content_to_dictionary_brotli.py migrated.db --yes

Requires the `brotli` CLI (>= 1.0) on PATH: no Python binding exposes custom
dictionaries, so encode and decode both shell out to it with -D.
"""

from __future__ import annotations

import argparse
import concurrent.futures as futures
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field

CHUNK_BYTES = 1024 * 1024
CONTINUATION = re.compile(r"^(.*)-(\d+)$")

# Set once per worker process: the dictionary lives in a file because the CLI
# takes a path, and writing it once per process beats once per row.
_DICTIONARY_PATH = ""


def _init_worker(dictionary: bytes) -> None:
    global _DICTIONARY_PATH
    handle, path = tempfile.mkstemp(prefix="brotli-dict-", suffix=".bin")
    with os.fdopen(handle, "wb") as out:
        out.write(dictionary)
    _DICTIONARY_PATH = path


def _brotli(args: list[str], payload: bytes) -> tuple[bool, bytes, str]:
    """Run the brotli CLI over stdin/stdout. Returns (ok, output, stderr)."""
    done = subprocess.run(["brotli", *args], input=payload, capture_output=True)
    return done.returncode == 0, done.stdout, done.stderr.decode("utf-8", "replace").strip()


def decode_plain(payload: bytes) -> tuple[bool, bytes]:
    ok, out, _ = _brotli(["-d", "-c"], payload)
    return ok, out


def decode_with_dictionary(payload: bytes) -> tuple[bool, bytes]:
    ok, out, _ = _brotli(["-d", "-D", _DICTIONARY_PATH, "-c"], payload)
    return ok, out


def encode_with_dictionary(payload: bytes, quality: int, window: int) -> tuple[bool, bytes, str]:
    return _brotli(
        ["-q", str(quality), "-w", str(window), "-D", _DICTIONARY_PATH, "-c", "-f"],
        payload,
    )


@dataclass
class Item:
    """One logical piece of content: a base row plus any continuation rows."""

    base_path: str
    base_id: int
    language_id: int
    content_type_id: int
    template_id: int
    # (row id, suffix number, byte length), ascending by suffix
    continuations: list[tuple[int, int, int]] = field(default_factory=list)
    base_bytes: int = 0

    @property
    def stored_bytes(self) -> int:
        return self.base_bytes + sum(n for _, _, n in self.continuations)

    @property
    def first_suffix(self) -> int:
        return self.continuations[0][1] if self.continuations else 1


@dataclass
class Result:
    base_path: str
    status: str  # migrated | already | unchanged | error
    slices: list[bytes] = field(default_factory=list)
    before: int = 0
    after: int = 0
    detail: str = ""


def migrate_item(item: Item, blobs: list[bytes], quality: int, window: int, only_if_smaller: bool) -> Result:
    """Decode an item, recompress it against the dictionary, and re-split it.

    Classification deliberately tries the *plain* decode first. Attaching no
    dictionary to a stream that needs one reliably fails, so a successful plain
    decode proves the row is not yet migrated; the reverse test is not safe,
    because a dictionary attached to a stream that never used one can decode to
    different bytes without erroring.
    """
    stored = b"".join(blobs)
    before = len(stored)

    ok, plaintext = decode_plain(stored)
    if not ok:
        ok_dict, _ = decode_with_dictionary(stored)
        if ok_dict:
            return Result(item.base_path, "already", before=before, after=before)
        return Result(item.base_path, "error", before=before, detail="decodes neither plainly nor with the dictionary")

    # A stream that decodes *both* ways is one the compressor never referenced the
    # dictionary for -- small, already-compressed payloads like a 1 KB GIF. It is
    # byte-identical in either form, so there is nothing to migrate, and skipping it
    # keeps a re-run from recompressing it for no gain.
    ok_dict, as_dict = decode_with_dictionary(stored)
    if ok_dict and as_dict == plaintext:
        return Result(item.base_path, "already", before=before, after=before)

    ok, recompressed, stderr = encode_with_dictionary(plaintext, quality, window)
    if not ok:
        return Result(item.base_path, "error", before=before, detail=f"compression failed: {stderr}")

    # The migration is only worth anything if it round-trips exactly.
    ok, roundtrip = decode_with_dictionary(recompressed)
    if not ok or roundtrip != plaintext:
        return Result(
            item.base_path,
            "error",
            before=before,
            detail="recompressed bytes do not decode back to the original content",
        )

    if only_if_smaller and len(recompressed) >= before:
        return Result(item.base_path, "unchanged", before=before, after=before,
                      detail=f"dictionary-compressed form is larger ({len(recompressed)} vs {before})")

    slices = [recompressed[i:i + CHUNK_BYTES] for i in range(0, len(recompressed), CHUNK_BYTES)] or [b""]
    return Result(item.base_path, "migrated", slices=slices, before=before, after=len(recompressed))


def load_items(connection: sqlite3.Connection) -> list[Item]:
    rows = connection.execute(
        """
        SELECT C.id, C.path, C.languageID, C.contentTypeID, C.templateId, LENGTH(C.content)
          FROM Content C
          JOIN ContentTypes CT ON CT.id = C.contentTypeID
         WHERE CT.compression = 'brotli'
        """
    ).fetchall()

    by_path = {path: row for row in rows for path in (row[1],)}
    items: dict[str, Item] = {}
    continuations: list[tuple[str, int, int, int]] = []

    for row_id, path, language_id, content_type_id, template_id, length in rows:
        match = CONTINUATION.match(path)
        # A continuation only counts as one if its base is itself a row; a path
        # that merely ends in -<digits> is ordinary content.
        if match and match.group(1) in by_path:
            continuations.append((match.group(1), row_id, int(match.group(2)), length))
        else:
            items[path] = Item(path, row_id, language_id, content_type_id, template_id, base_bytes=length)

    for base_path, row_id, suffix, length in continuations:
        owner = items.get(base_path)
        if owner is not None:
            owner.continuations.append((row_id, suffix, length))

    for item in items.values():
        item.continuations.sort(key=lambda entry: entry[1])

    return sorted(items.values(), key=lambda item: item.base_path)


def read_blobs(connection: sqlite3.Connection, item: Item) -> list[bytes]:
    ids = [item.base_id] + [row_id for row_id, _, _ in item.continuations]
    placeholders = ",".join("?" * len(ids))
    found = dict(connection.execute(f"SELECT id, content FROM Content WHERE id IN ({placeholders})", ids).fetchall())
    return [found[row_id] for row_id in ids]


def write_item(connection: sqlite3.Connection, item: Item, slices: list[bytes], renumber: bool) -> tuple[int, int]:
    """Write an item's new slices back. Returns (rows inserted, rows deleted)."""
    connection.execute("UPDATE Content SET content = ? WHERE id = ?", (slices[0], item.base_id))

    start = 1 if renumber else item.first_suffix
    wanted = list(enumerate(slices[1:], start=start))
    existing = {suffix: row_id for row_id, suffix, _ in item.continuations}
    inserted = deleted = 0

    for suffix, payload in wanted:
        row_id = existing.pop(suffix, None)
        if row_id is None:
            connection.execute(
                """
                INSERT INTO Content (path, languageID, content, contentTypeID, templateId)
                VALUES (?, ?, ?, ?, ?)
                """,
                (f"{item.base_path}-{suffix}", item.language_id, payload, item.content_type_id, item.template_id),
            )
            inserted += 1
        else:
            connection.execute("UPDATE Content SET content = ? WHERE id = ?", (payload, row_id))

    # Whatever is left over described slices the new stream no longer needs.
    for row_id in existing.values():
        connection.execute("DELETE FROM Content WHERE id = ?", (row_id,))
        deleted += 1

    return inserted, deleted


def human(n: float) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(n) < 1024 or unit == "GiB":
            return f"{n:,.1f} {unit}" if unit != "B" else f"{n:,.0f} B"
        n /= 1024
    return f"{n:,.1f} GiB"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("database", help="documentation.db to migrate (operate on a copy)")
    parser.add_argument("--yes", action="store_true", help="actually write; without it the run is a dry run")
    parser.add_argument("--dry-run", action="store_true", help="explicit no-write run (the default anyway)")
    parser.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2)), help="parallel compressors")
    parser.add_argument("--quality", type=int, default=11, help="brotli quality (default 11, as the pipeline uses)")
    parser.add_argument("--window", type=int, default=22, help="brotli window log (default 22, the portable maximum)")
    parser.add_argument("--limit", type=int, default=0, help="stop after this many items (for a smoke test)")
    parser.add_argument("--path", default="", help="only items whose base path contains this substring")
    parser.add_argument("--batch", type=int, default=200, help="items per write transaction")
    parser.add_argument(
        "--only-if-smaller",
        action="store_true",
        help="leave a row alone when its dictionary-compressed form is not smaller",
    )
    parser.add_argument(
        "--renumber-continuations",
        action="store_true",
        help="write continuation rows from -1 rather than preserving existing numbering "
             "(fixes ADFA-5170's unreachable slices; changes behaviour, so opt-in)",
    )
    args = parser.parse_args()
    write = args.yes and not args.dry_run

    connection = sqlite3.connect(args.database)
    connection.execute("PRAGMA foreign_keys = ON")

    dictionary_row = connection.execute("SELECT data FROM CompressionDictionary WHERE id = 1").fetchone()
    if dictionary_row is None or not dictionary_row[0]:
        print("error: this database has no CompressionDictionary row to migrate against", file=sys.stderr)
        return 2
    dictionary = dictionary_row[0]

    items = load_items(connection)
    if args.path:
        items = [item for item in items if args.path in item.base_path]
    if args.limit:
        items = items[: args.limit]
    chunked = [item for item in items if item.continuations]

    print(f"database        {args.database}")
    print(f"dictionary      {human(len(dictionary))}")
    print(f"items           {len(items):,} ({len(chunked)} of them stored as multiple slices)")
    print(f"stored now      {human(sum(item.stored_bytes for item in items))}")
    print(f"workers         {args.workers}   quality {args.quality}   window {args.window}")
    print(f"mode            {'WRITING' if write else 'dry run (pass --yes to write)'}")
    if chunked and not args.renumber_continuations:
        starts = sorted({item.first_suffix for item in chunked})
        print(f"continuations   preserving existing numbering (starts at {starts}); "
              f"--renumber-continuations rewrites from -1")
    print()

    counts = {"migrated": 0, "already": 0, "unchanged": 0, "error": 0}
    before_total = after_total = 0
    inserted_total = deleted_total = 0
    errors: list[Result] = []
    started = time.time()

    with futures.ProcessPoolExecutor(args.workers, initializer=_init_worker, initargs=(dictionary,)) as pool:
        for offset in range(0, len(items), args.batch):
            batch = items[offset : offset + args.batch]
            pending = {
                pool.submit(
                    migrate_item, item, read_blobs(connection, item), args.quality, args.window, args.only_if_smaller
                ): item
                for item in batch
            }

            for future in futures.as_completed(pending):
                item = pending[future]
                result = future.result()
                counts[result.status] += 1
                before_total += result.before
                after_total += result.after or result.before

                if result.status == "error":
                    errors.append(result)
                elif result.status == "migrated" and write:
                    inserted, deleted = write_item(connection, item, result.slices, args.renumber_continuations)
                    inserted_total += inserted
                    deleted_total += deleted

            if write:
                connection.commit()

            done = min(offset + args.batch, len(items))
            elapsed = time.time() - started
            rate = done / elapsed if elapsed else 0
            remaining = (len(items) - done) / rate if rate else 0
            print(
                f"\r{done:,}/{len(items):,} items  {rate:5.1f}/s  "
                f"eta {remaining/60:4.1f} min  saved {human(before_total - after_total)}",
                end="",
                flush=True,
            )

    print("\n")
    print(f"migrated        {counts['migrated']:,}")
    print(f"already         {counts['already']:,}")
    if counts["unchanged"]:
        print(f"left alone      {counts['unchanged']:,} (not smaller with the dictionary)")
    print(f"errors          {counts['error']:,}")
    if write:
        print(f"rows inserted   {inserted_total}    rows deleted    {deleted_total}")
    print(f"stored before   {human(before_total)}")
    print(f"stored after    {human(after_total)}")
    if before_total:
        print(f"saved           {human(before_total - after_total)} ({100 * (before_total - after_total) / before_total:.1f}%)")
    print(f"took            {(time.time() - started)/60:.1f} min")

    for result in errors[:20]:
        print(f"  error: {result.base_path}: {result.detail}", file=sys.stderr)
    if len(errors) > 20:
        print(f"  ... and {len(errors) - 20} more", file=sys.stderr)

    if write:
        connection.commit()
        print("\nRun VACUUM to reclaim the freed pages:  sqlite3 %s 'VACUUM;'" % args.database)
    else:
        connection.rollback()
        print("\nNothing written. Re-run with --yes on a copy to apply.")

    connection.close()
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

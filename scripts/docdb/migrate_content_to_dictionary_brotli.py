#!/usr/bin/env python3
"""Repair mislabelled binary rows in documentation.db, then recompress its Brotli
Content rows against the shared dictionary.

Three phases, in this order, because each one changes what the next one sees:

  1. retype   -- rows that claim to be text but hold a GIF/PNG/JPEG/QuickTime
                 payload (ADFA-5221). Their declared type is `text/plain`, whose
                 ContentTypes row says `brotli`, so they were pointlessly
                 compressed *and* are served as `Content-Type: text/plain`. The
                 fix is to store the plaintext and point the row at the honest
                 type, whose compression is `none`.
  2. renumber -- chunked items whose continuation rows start at -2 while
                 WebServer's reassembly loop starts at -1 (ADFA-5171), so they
                 currently serve as their first 1 MiB and nothing more.
  3. migrate  -- rewrite every `ContentTypes.compression = 'brotli'` row so it is
                 compressed against the database's own dictionary rather than
                 plainly. WebServer tries a dictionary-attached decode first and
                 falls back to a plain one, so a half-migrated database mostly
                 still serves -- but only mostly: a plain row can decode against
                 the dictionary to *different bytes without erroring*, so the
                 fallback never fires and the page serves silent garbage. A run
                 that declares the version therefore has to finish. The script
                 says so at the end when it did not, and refuses a --path/--limit
                 --yes migrate outright, since a scoped run may not make the
                 declaration and content migrated without it cannot be decoded
                 at all.

Phase 1 feeds phase 3 for free: a row retyped to `image/gif` inherits that type's
`compression = 'none'`, so phase 3's `compression = 'brotli'` selection simply
stops seeing it. No exclusion list is needed.

Properties of the data that shape this script, all verified against the 20-Aug
database rather than assumed:

  * Content over 1 MiB is *not* stored as independently compressed pieces. The
    rows are raw 1 MiB slices of one stream: `path`, then `path-N` continuations.
    A slice on its own does not decode. So the unit of work is a logical item --
    a base row plus its continuations -- concatenated, decoded, rewritten, and
    re-split. Treating such rows one at a time would destroy the content.

  * Neither decode classifies a row on its own. Some rows decode identically with
    and without the dictionary (tiny payloads the compressor found nothing to
    reference in); others decode *both* ways to different bytes, neither erroring
    -- a 92-byte stored stream of repetitive HTML decodes plainly to the real
    content and, with the dictionary attached, to the same length of garbage.
    What settles those is the version the database declared before the run
    started: below the dictionary version no row is dictionary-compressed, so the
    plain decode is the content; at or above it, the dictionary decode is.
    Whether there is anything to *do* is then a separate question, decided by
    re-encoding and comparing against the stored bytes -- not by how the stored
    bytes happen to decode, which says nothing about what re-encoding would gain.

  * Extensions nominate phase 1's candidates; magic bytes decide. A row is
    retyped to what its payload actually is, not to what its name suggests, and a
    name/content disagreement is reported rather than trusted. The four `.mov`
    files are `ftypqt` QuickTime, not ISO-BMFF, so --mov-type picks between the
    honest `video/quicktime` (inserted into ContentTypes if absent) and the
    `video/mp4` that Chromium is likelier to actually play.

  * `Content` has a real UNIQUE constraint on `path` (the schema's `UNIQUE('path')`
    quotes the identifier but does enforce it), so a renumbering mistake fails
    loudly instead of duplicating a row. The `AddBook`/`DeleteBook` triggers fire
    only for paths ending `.pdf`, which continuation paths never do.

Parallel by default: compression at quality 11 is the whole cost (~4 GB of
plaintext), and it parallelises perfectly across cores.

Usage:
    # inspect: what all three phases would change, nothing written
    migrate_content_to_dictionary_brotli.py documentation.db --dry-run

    # do it, on a copy
    cp documentation.db migrated.db
    migrate_content_to_dictionary_brotli.py migrated.db --yes
    sqlite3 migrated.db 'VACUUM;'

    # just the data repair, leaving compression alone
    migrate_content_to_dictionary_brotli.py migrated.db --yes --phases retype,renumber

Requires the `brotli` CLI (>= 1.0) on PATH: no Python binding exposes custom
dictionaries, so encode and decode both shell out to it with -D.
"""

from __future__ import annotations

import argparse
import atexit
import concurrent.futures as futures
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field

CHUNK_BYTES = 1024 * 1024

# Each worker holds ~3 copies of an item's plaintext while it works; the largest chunked items in
# this database are ~160 MB, so the ceiling is memory, not cores.
MAX_WORKERS = 64

# WebServer looks continuations up with "languageId = 1" hardcoded, whatever the base row says.
CONTINUATION_LANGUAGE_ID = 1


# Every phase calls load_items, and verify_retype calls it again, so a note about the shape of the
# data would otherwise print three or four times per run for the same row.
_REPORTED: set[str] = set()


def note_once(message: str) -> None:
    if message not in _REPORTED:
        _REPORTED.add(message)
        print(message)


def is_text_type(value: str) -> bool:
    """Whether a ContentTypes.value is a text type, matched at the boundary.

    `startswith("text")` also matches `textual/example`; the database's bare `text`
    oddity is why the exact match is needed alongside `text/`. Same rule as the
    app's ContentTypeHeaders (ADFA-5241).
    """
    return value == "text" or value.startswith("text/")
# The suffix must render back to the path it was parsed from. A zero-padded "-007" parses to 7
# and renders as "-7", so the clash check probes a path no row holds while write_item UPDATEs the
# row still named "-007" and INSERTs the next slice at "-8" -- one item split across two naming
# schemes, reported as a success. A padded sibling is not one of ours; leave it standalone.
CONTINUATION = re.compile(r"^(.*)-(0|[1-9]\d*)$")
ALL_PHASES = ("retype", "renumber", "migrate")

# Extensions worth a second look when a row claims to be text. The extension only
# nominates a candidate -- sniff() decides what the row actually holds.
BINARY_EXTENSIONS = (
    ".gif", ".png", ".jpg", ".jpeg", ".webp", ".ico", ".bmp",
    ".mov", ".mp4", ".m4v", ".pdf",
    ".woff", ".woff2", ".ttf", ".otf", ".wasm",
)

# Set once per worker process: the dictionary lives in a file because the CLI
# takes a path, and writing it once per process beats once per row.
_DICTIONARY_PATH = ""


def _init_worker(dictionary: bytes) -> None:
    global _DICTIONARY_PATH
    handle, path = tempfile.mkstemp(prefix="brotli-dict-", suffix=".bin")
    with os.fdopen(handle, "wb") as out:
        out.write(dictionary)
    _DICTIONARY_PATH = path
    # One file per worker, so without this a run leaves `--workers` copies of the
    # dictionary in the temp directory forever. Survives a normal pool shutdown; a
    # kill -9 of a worker still leaks its file.
    atexit.register(_remove_quietly, path)


def _remove_quietly(path: str) -> None:
    try:
        os.remove(path)
    except OSError:
        pass


def _brotli(args: list[str], payload: bytes) -> tuple[bool, bytes, str]:
    """Run the brotli CLI over stdin/stdout. Returns (ok, output, stderr)."""
    done = subprocess.run(["brotli", *args], input=payload, capture_output=True)
    return done.returncode == 0, done.stdout, done.stderr.decode("utf-8", "replace").strip()


# Both decoders hand back stderr, as encode_with_dictionary always has. Discarding it meant every
# non-zero exit read as "this row does not decode" -- so a vanished dictionary tempfile, an
# OOM-killed child or a missing brotli binary were all reported to the operator as data corruption
# in the content, which is the one explanation that sends them looking in the wrong place.
def decode_plain(payload: bytes) -> tuple[bool, bytes, str]:
    return _brotli(["-d", "-c"], payload)


def decode_with_dictionary(payload: bytes) -> tuple[bool, bytes, str]:
    return _brotli(["-d", "-D", _DICTIONARY_PATH, "-c"], payload)


def encode_with_dictionary(payload: bytes, quality: int, window: int) -> tuple[bool, bytes, str]:
    return _brotli(
        ["-q", str(quality), "-w", str(window), "-D", _DICTIONARY_PATH, "-c", "-f"],
        payload,
    )


def sniff(payload: bytes) -> str:
    """The MIME type the bytes themselves declare, or '' if unrecognised."""
    if payload[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if payload[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if payload[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if payload[:4] == b"RIFF" and payload[8:12] == b"WEBP":
        return "image/webp"
    if payload[:2] == b"BM":
        return "image/bmp"
    if payload[:4] == b"\x00\x00\x01\x00":
        return "image/x-icon"
    if payload[:4] == b"%PDF":
        return "application/pdf"
    if payload[4:8] == b"ftyp":
        # The brand distinguishes a QuickTime container from ISO-BMFF/MP4.
        return "video/quicktime" if payload[8:12] == b"qt  " else "video/mp4"
    if payload[:4] == b"wOF2":
        return "font/woff2"
    if payload[:4] == b"wOFF":
        return "font/woff"
    if payload[:4] == b"OTTO":
        return "font/otf"
    if payload[:4] in (b"\x00\x01\x00\x00", b"true", b"ttcf"):
        return "font/ttf"
    if payload[:4] == b"\x00asm":
        return "application/wasm"
    return ""


def slice_stream(payload: bytes) -> list[bytes]:
    return [payload[i:i + CHUNK_BYTES] for i in range(0, len(payload), CHUNK_BYTES)] or [b""]


@dataclass
class Item:
    """One logical piece of content: a base row plus any continuation rows."""

    base_path: str
    base_id: int
    language_id: int
    content_type_id: int
    template_id: int
    content_type: str = ""
    compression: str = ""
    # (row id, suffix number, byte length), ascending by suffix
    continuations: list[tuple[int, int, int]] = field(default_factory=list)
    base_bytes: int = 0

    @property
    def stored_bytes(self) -> int:
        return self.base_bytes + sum(n for _, _, n in self.continuations)

    @property
    def first_suffix(self) -> int:
        return self.continuations[0][1] if self.continuations else 1

    @property
    def suffixes(self) -> list[int]:
        return [suffix for _, suffix, _ in self.continuations]


@dataclass
class Inspection:
    """Phase 1's verdict on one candidate row."""

    base_path: str
    status: str  # retype | keep | error
    sniffed: str = ""
    slices: list[bytes] = field(default_factory=list)
    before: int = 0
    after: int = 0
    detail: str = ""


@dataclass
class Result:
    """Phase 3's verdict on one item."""

    base_path: str
    status: str  # migrated | already | error
    slices: list[bytes] = field(default_factory=list)
    before: int = 0
    after: int = 0
    detail: str = ""
    sniffed: str = ""  # set when a text-typed row turns out to hold binary


def inspect_item(item: Item, blobs: list[bytes]) -> Inspection:
    """Decide what a text-typed candidate actually holds, and hand back its plaintext."""
    stored = b"".join(blobs)
    before = len(stored)

    if item.compression == "brotli":
        ok, payload, plain_err = decode_plain(stored)
        if not ok:
            ok, payload, dict_err = decode_with_dictionary(stored)
            if not ok:
                return Inspection(item.base_path, "error", before=before,
                                  detail=f"decodes neither plainly ({plain_err or 'no stderr'}) nor "
                                         f"with the dictionary ({dict_err or 'no stderr'})")
    else:
        payload = stored

    kind = sniff(payload)
    if not kind:
        return Inspection(item.base_path, "keep", before=before, after=before,
                          detail=f"declared {item.content_type}, and the payload carries no binary signature")

    return Inspection(item.base_path, "retype", sniffed=kind, slices=slice_stream(payload),
                      before=before, after=len(payload))


def migrate_item(item: Item, blobs: list[bytes], quality: int, window: int,
                 db_was_migrated: bool) -> Result:
    """Decode an item, recompress it against the dictionary, and re-split it.

    Both decodes are attempted, because neither one alone classifies a row. Attaching a
    dictionary to a stream that never used one *usually* throws -- but not always, and the same
    is true the other way, so a decode that succeeds is evidence and not proof. [db_was_migrated]
    is what the database declared BEFORE this run started, and it settles the case where both
    decodes succeed and disagree.
    """
    stored = b"".join(blobs)
    before = len(stored)

    ok, plaintext, plain_err = decode_plain(stored)
    ok_dict, as_dict, dict_err = decode_with_dictionary(stored)

    # The mislabel census is computed on whichever decode is authoritative, and on every path that
    # returns. Leaving it unset on the "already" branches meant a re-run over an already-migrated
    # database reported zero mislabelled rows -- not because there were none, but because the only
    # branch that filled it in was the one a migrated row never takes.
    def census(content: bytes) -> str:
        return sniff(content) if is_text_type(item.content_type) else ""

    if not ok:
        if ok_dict:
            return Result(item.base_path, "already", before=before, after=before,
                          sniffed=census(as_dict))
        return Result(item.base_path, "error", before=before,
                      detail=f"decodes neither plainly ({plain_err or 'no stderr'}) nor with the "
                             f"dictionary ({dict_err or 'no stderr'})")

    # Both decodes succeeded and returned different bytes, neither erroring. This is not exotic:
    # a short stored stream (92 B of highly repetitive HTML, say) decodes plainly to the real
    # content and, with the dictionary attached, to the same LENGTH of garbage with exit 0.
    # Nothing in the stream says which is which, so the decision comes from the database rather
    # than the row: the version it declared before this run started. Below the dictionary version,
    # no row is dictionary-compressed by contract, so the plain decode is the content. At or above
    # it, they all are, so the dictionary decode is -- and the row needs nothing done to it.
    # Recompressing the wrong one of these stores garbage that the round-trip check below then
    # happily validates, because it round-trips the garbage.
    if ok_dict and as_dict != plaintext:
        if db_was_migrated:
            return Result(item.base_path, "already", before=before, after=before,
                          sniffed=census(as_dict))

    # Decoding every row here anyway makes a mislabel sweep free: report a
    # text-typed row whose payload is recognisably binary, whatever its name.
    mislabelled = census(plaintext)

    ok, recompressed, stderr = encode_with_dictionary(plaintext, quality, window)
    if not ok:
        return Result(item.base_path, "error", before=before, detail=f"compression failed: {stderr}")

    # The migration is only worth anything if it round-trips exactly.
    ok, roundtrip, roundtrip_err = decode_with_dictionary(recompressed)
    if not ok or roundtrip != plaintext:
        return Result(
            item.base_path,
            "error",
            before=before,
            detail="recompressed bytes do not decode back to the original content"
                   + (f" ({roundtrip_err})" if roundtrip_err else ""),
        )

    # "Nothing to do" is a property of the RE-ENCODED stream, not the stored one. The test this
    # replaces decoded the stored stream twice and called any row that decoded both ways "already
    # migrated" -- but a row stored at q1 also decodes identically with the dictionary attached,
    # while re-encoding it at q11 against the dictionary is 36% smaller (5,858 -> 3,748 B on a real
    # doc page). Every such row was skipped permanently and counted as a success: a whole-database
    # run over content stored at q1 reported "already 300, saved 0 B" and exited 0 having done
    # nothing. Comparing the bytes we would actually write is the question being asked.
    if recompressed == stored:
        return Result(item.base_path, "already", before=before, after=before, sniffed=mislabelled)

    return Result(item.base_path, "migrated", slices=slice_stream(recompressed),
                  before=before, after=len(recompressed), sniffed=mislabelled)


def load_items(connection: sqlite3.Connection, predicate: str) -> list[Item]:
    rows = connection.execute(
        f"""
        SELECT C.id, C.path, C.languageID, C.contentTypeID, C.templateId,
               -- IFNULL: a row with NULL content has NULL length, which made the byte totals
               -- (and so the phase summary) throw before read_blobs could report the row.
               -- CAST to BLOB first: LENGTH() on a TEXT-class value counts CHARACTERS, so a row
               -- holding multi-byte UTF-8 measured short, and this number decides what counts as a
               -- 1 MiB chunk base. The app measures the same rows with getBlob().size, i.e. bytes.
               IFNULL(LENGTH(CAST(C.content AS BLOB)), 0), CT.value, CT.compression
          FROM Content C
          JOIN ContentTypes CT ON CT.id = C.contentTypeID
         WHERE {predicate}
        """
    ).fetchall()

    # Sibling detection reads the WHOLE table, not just this phase's selection. Scoping it to the
    # predicate made a continuation typed outside the phase invisible: the item loaded with the
    # wrong slice set, the unselected row survived unmigrated and unreported, and a base whose
    # "-1" was filtered out reported first_suffix 2, so write_item re-created the stream starting
    # at -2 -- re-introducing the ADFA-5171 defect phase 2 had just normalised.
    all_rows = connection.execute(
        "SELECT id, path, languageID, contentTypeID, IFNULL(LENGTH(CAST(content AS BLOB)), 0) FROM Content"
    ).fetchall()
    base_bytes_by_path = {path: length for _, path, _, _, length in all_rows}
    selected_ids = {row[0] for row in rows}
    unselected_by_id = {
        row_id: (path, language_id, type_id)
        for row_id, path, language_id, type_id, _ in all_rows
        if row_id not in selected_ids
    }
    rows_by_id = {row[0]: row for row in rows}
    items: dict[str, Item] = {}
    continuations: list[tuple[str, int, int, int]] = []

    def standalone(row: tuple) -> Item:
        """An Item carrying the row's own metadata -- a row ungrouped later (a disproved
        continuation, an orphan) is an independent page, and stamping the would-be base's
        contentTypeID/languageID onto it would relabel a foreign page."""
        row_id, path, language_id, type_id, template_id, length, type_value, compression = row
        return Item(
            base_path=path,
            base_id=row_id,
            language_id=language_id,
            content_type_id=type_id,
            template_id=template_id,
            content_type=type_value,
            compression=compression,
            base_bytes=length,
        )

    for row in rows:
        row_id, path, length = row[0], row[1], row[5]
        match = CONTINUATION.match(path)
        # A "-<digits>" sibling is a naming coincidence until the base row proves otherwise, and
        # the proof is the base holding exactly CHUNK_BYTES -- the app's own chunk-detection rule.
        # Grouping on the name alone let a rewrite of the greedy base (retype or migrate) absorb an
        # independent page's bytes and delete its row; here every phase inherits the test.
        if match and base_bytes_by_path.get(match.group(1)) == CHUNK_BYTES:
            continuations.append((match.group(1), row_id, int(match.group(2)), length))
        else:
            items[path] = standalone(row)

    orphans: list[tuple[str, int, int, int]] = []
    for base_path, row_id, suffix, length in continuations:
        owner = items.get(base_path)
        if owner is not None:
            owner.continuations.append((row_id, suffix, length))
        else:
            # The greedy base is itself a continuation, so this row has no owner to be a slice
            # of. Silently dropping it meant a row that was never migrated, never counted and
            # never reported -- in a database the run then declares version 2 while the row is
            # still plain brotli, which can decode against the dictionary to wrong bytes.
            orphans.append((base_path, row_id, suffix, length))

    for item in items.values():
        item.continuations.sort(key=lambda entry: entry[1])

    # A base of exactly CHUNK_BYTES is not enough on its own. A genuine slice set has every slice
    # except the last at exactly CHUNK_BYTES, because that is how the writer splits; an 11-byte "-2"
    # followed by a "-3" is provably not one. Without this, a real page that happens to be exactly
    # 1 MiB, sitting next to independently named "-2"/"-3" pages, was grouped with them and phase 2
    # renamed those pages into its slice slots -- both URLs 404, and the app appends a foreign page's
    # bytes on reassembly. Verified against the real schema before and after this check.
    def proven_slices(item: Item) -> str:
        """'' when these really are slices of `item`, else why they cannot be proven to be."""
        head = item.continuations[:-1]
        if not all(length == CHUNK_BYTES for _, _, length in head):
            return "a slice before the last is not exactly CHUNK_BYTES"
        # `head` is empty when there is exactly one continuation, so the length rule above is
        # vacuously true and proves nothing -- that is how an unrelated 11-byte "k/guide-2" got
        # grouped under a real 1 MiB "k/guide" and renamed into its slice slot by phase 2, 404ing
        # its own URL and corrupting the reassembly of the base. Slices of one payload are written
        # by write_item with a single contentTypeID and languageID, so a sibling that disagrees
        # with the base on either is somebody else's page. This cannot separate a coincidence that
        # happens to match on both; phase 3's decode is the backstop for that.
        for row_id, _, _ in item.continuations:
            row = rows_by_id[row_id]
            if row[3] != item.content_type_id:
                return f"{row[1]} is contentTypeID {row[3]}, not the base's {item.content_type_id}"
            if row[2] not in (CONTINUATION_LANGUAGE_ID, item.language_id):
                return (f"{row[1]} is languageID {row[2]}, which is neither "
                        f"{CONTINUATION_LANGUAGE_ID} nor the base's {item.language_id}")
        return ""

    for item in list(items.values()):
        if not item.continuations:
            continue
        unproven = proven_slices(item)
        if not unproven:
            continue
        # Reported, not silently dropped. A genuine slice set whose rows disagree with the base is
        # indistinguishable here from an independent page that merely shares the name, and the two
        # want opposite treatment -- normalise, or keep well away. Renaming the wrong one destroys a
        # live page, so the safe branch is taken and the operator is told which item to look at.
        note_once(
            f"      note: {item.base_path} has {len(item.continuations)} '-N' sibling(s) that cannot "
            f"be proven to be its slices ({unproven}); treated as independent pages. If they really "
            f"are slices, the page will serve only its first {human(CHUNK_BYTES)} until they are fixed by hand"
        )
        for row_id, _, _ in item.continuations:
            row = rows_by_id[row_id]
            items[row[1]] = standalone(row)
        item.continuations.clear()

    # A sibling this phase did not select is still a row that exists. Left unmentioned it survives
    # every run unmigrated while the database goes on to declare the dictionary version. Indexed
    # by base path first: probing the whole unselected set per item is O(items x rows), and both
    # are ~30k here.
    unselected_slices: dict[str, list[int]] = {}
    for path, _, _ in unselected_by_id.values():
        match = CONTINUATION.match(path)
        if match:
            unselected_slices.setdefault(match.group(1), []).append(int(match.group(2)))

    for item in items.values():
        if not item.continuations:
            continue
        owned = {suffix for _, suffix, _ in item.continuations}
        for suffix in sorted(set(unselected_slices.get(item.base_path, ())) - owned):
            note_once(
                f"      note: {item.base_path}-{suffix} names a slice of {item.base_path} but this "
                f"phase did not select it; it is left as it is and does not count towards the migration"
            )

    # An orphan is still a row this phase selected, so it becomes its own item and migrates
    # normally. If it really is a stray slice of some stream, its bytes decode neither plainly
    # nor with the dictionary, and it surfaces as an error instead of silently surviving a run
    # that declares version 2.
    for base_path, row_id, suffix, _ in orphans:
        row = rows_by_id[row_id]
        items[row[1]] = standalone(row)
        note_once(
            f"      note: {base_path}-{suffix} looks like a continuation of {base_path}, which is "
            f"not itself a migratable row; treated as an independent page"
        )

    return sorted(items.values(), key=lambda item: item.base_path)


def read_blobs(connection: sqlite3.Connection, item: Item) -> list[bytes] | None:
    """An item's slices in order, or None when a row has vanished or holds NULL content.

    None rather than an exception: a single unreadable row used to abort the whole run from
    inside a worker, leaving earlier batches committed and printing no summary at all.
    """
    ids = [item.base_id] + [row_id for row_id, _, _ in item.continuations]
    placeholders = ",".join("?" * len(ids))
    # CAST to BLOB so a TEXT-class row arrives as bytes: sqlite3 hands back `str` otherwise, which
    # fails in the worker at b"".join(blobs). Same reason as the LENGTH() casts in load_items -- the
    # app reads these rows with getBlob(), and so should this.
    found = dict(connection.execute(
        f"SELECT id, CAST(content AS BLOB) FROM Content WHERE id IN ({placeholders})", ids
    ).fetchall())
    blobs = [found.get(row_id) for row_id in ids]
    return None if any(blob is None for blob in blobs) else blobs


class PathClash(Exception):
    """A continuation path this item needs is owned by some other row."""


def continuation_clash(connection: sqlite3.Connection, item: Item, slices: int, renumber: bool) -> str:
    """The first target path owned by a foreign row, described; '' if the write is safe."""
    start = 1 if renumber else item.first_suffix
    own = {row_id for row_id, _, _ in item.continuations}
    for suffix in range(start, start + slices - 1):
        target = f"{item.base_path}-{suffix}"
        row = connection.execute("SELECT id FROM Content WHERE path = ?", (target,)).fetchone()
        if row and row[0] not in own:
            return f"{target} already exists and belongs to another row"
    return ""


def write_item(
    connection: sqlite3.Connection,
    item: Item,
    slices: list[bytes],
    renumber: bool,
    content_type_id: int | None = None,
) -> tuple[int, int]:
    """Write an item's new slices back. Returns (rows inserted, rows deleted).

    Raises PathClash if a continuation path is owned by a foreign row. renumber_item makes the
    same check before it moves anything; this one did not, so an occupied path surfaced as a bare
    sqlite3.IntegrityError out of the middle of a phase, with earlier batches already committed
    and no summary printed -- the failure mode the batching was introduced to avoid.
    """
    clash = continuation_clash(connection, item, len(slices), renumber)
    if clash:
        raise PathClash(f"{item.base_path}: {clash}; left alone")

    type_id = item.content_type_id if content_type_id is None else content_type_id
    connection.execute(
        "UPDATE Content SET content = ?, contentTypeID = ? WHERE id = ?",
        (slices[0], type_id, item.base_id),
    )

    start = 1 if renumber else item.first_suffix
    wanted = list(enumerate(slices[1:], start=start))
    existing = {suffix: row_id for row_id, suffix, _ in item.continuations}
    inserted = deleted = 0

    # Retained rows are renumbered by taking the slot they now hold, so drop every
    # old continuation path first and re-create what the new stream needs. Deleting
    # before inserting keeps the UNIQUE(path) constraint out of the way when the
    # numbering shifts.
    if renumber and item.first_suffix != 1:
        for row_id in existing.values():
            connection.execute("DELETE FROM Content WHERE id = ?", (row_id,))
            deleted += 1
        existing = {}

    for suffix, payload in wanted:
        row_id = existing.pop(suffix, None)
        if row_id is None:
            connection.execute(
                """
                INSERT INTO Content (path, languageID, content, contentTypeID, templateId)
                VALUES (?, ?, ?, ?, ?)
                """,
                # languageID 1, not the base row's: WebServer's continuation query is
                # "WHERE path = ? AND languageId = 1" (documented in
                # docs/documentation-database.md), so a continuation inserted under any other
                # language is invisible and the page truncates at its first 1 MiB -- the exact
                # ADFA-5171 symptom this script exists to remove.
                (f"{item.base_path}-{suffix}", CONTINUATION_LANGUAGE_ID, payload, type_id, item.template_id),
            )
            inserted += 1
        else:
            connection.execute(
                # languageID too, not just the bytes: a continuation row that predates this script
                # can carry the base row's language, and WebServer's continuation query filters on
                # languageId = 1 -- so reusing the row without normalising it leaves the page
                # truncated exactly as an unnumbered continuation would.
                "UPDATE Content SET content = ?, contentTypeID = ?, languageID = ? WHERE id = ?",
                (payload, type_id, CONTINUATION_LANGUAGE_ID, row_id),
            )

    # Whatever is left over described slices the new stream no longer needs.
    for row_id in existing.values():
        connection.execute("DELETE FROM Content WHERE id = ?", (row_id,))
        deleted += 1

    return inserted, deleted


# The MAJOR the app requires before it will attach the dictionary at all
# (DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY). Migrating content without
# declaring this leaves a database whose every brotli row fails to decode: WebServer gates on the
# declared version, not on the presence of CompressionDictionary, so it never attaches the
# dictionary and the plain decode then throws "corrupted input" on every migrated row.
DICTIONARY_MAJOR_VERSION = 2

VERSION_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS DocumentationDatabaseVersion (
  major      INT NOT NULL,
  minor      INT NOT NULL,
  patch      INT NOT NULL,
  who        TEXT NOT NULL,
  comment    TEXT NOT NULL,
  changeTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)
"""


def declared_major(connection: sqlite3.Connection) -> int | None:
    """The MAJOR this database declares, or None when it declares none.

    Reads the row with the highest rowid: the table is append-only by contract
    (docs/documentation-database.md), so the row inserted last is the current version -- the same
    row the app's DatabaseVersionResolver reads (ADFA-5220).
    """
    if not table_exists(connection, "DocumentationDatabaseVersion"):
        return None
    row = connection.execute(
        "SELECT major FROM DocumentationDatabaseVersion ORDER BY rowid DESC LIMIT 1"
    ).fetchone()
    return row[0] if row is not None and row[0] is not None else None


def may_declare_version(args) -> str:
    """'' if this run may declare MAJOR 2, else why it may not.

    The declaration tells the app every brotli row is dictionary-compressed, and it applies to the
    whole database -- so only a run that considered the whole database may make it. A --path or
    --limit run migrates a handful and would leave the rest plain while claiming otherwise; the app
    then attaches the dictionary to those rows, and while most throw and fall back, a fraction
    decode without error to *different bytes*. That is silent wrong content, which is worse than the
    unmigrated state it replaces.
    """
    if args.path:
        return f"the run was scoped by --path {args.path!r}"
    if args.limit:
        return f"the run was scoped by --limit {args.limit}"
    return ""


def declare_dictionary_version(connection: sqlite3.Connection) -> None:
    """Records that this database's brotli content is dictionary-compressed.

    Written in the same transaction as the first batch of migrated content, because the two facts
    have to travel together: content compressed against the dictionary, and a version saying so.
    The log is append-only by contract (docs/documentation-database.md, DatabaseVersionResolver):
    each change is another INSERT and the row inserted last is the current version, so prior
    version rows are history to keep, never state to replace.
    """
    connection.execute(VERSION_TABLE_SQL)
    connection.execute(
        "INSERT INTO DocumentationDatabaseVersion (major, minor, patch, who, comment) VALUES (?, 0, 0, ?, ?)",
        (
            DICTIONARY_MAJOR_VERSION,
            "migrate_content_to_dictionary_brotli.py",
            "Content rows compressed against CompressionDictionary",
        ),
    )


def table_exists(connection: sqlite3.Connection, name: str) -> bool:
    """Whether `name` is a table in this database -- checked the way WebServer does."""
    found = connection.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (name,)
    ).fetchone()
    return found is not None


def content_types(connection: sqlite3.Connection) -> dict[str, tuple[int, str]]:
    return {
        value: (type_id, compression)
        for type_id, value, compression in connection.execute("SELECT id, value, compression FROM ContentTypes")
    }


def renumber_item(connection: sqlite3.Connection, item: Item, write: bool) -> str:
    """Shift an item's continuations onto -1. Returns a note, or ''."""
    shift = item.first_suffix - 1
    if shift == 0:
        return ""

    expected = list(range(item.first_suffix, item.first_suffix + len(item.continuations)))
    if item.suffixes != expected:
        return f"continuations are not contiguous ({item.suffixes}); left alone"

    # A row this item already owns is not a clash: it is the one being moved out of
    # that slot. Only a foreign row occupying a target path blocks the shift.
    own = {row_id for row_id, _, _ in item.continuations}
    for _, suffix, _ in item.continuations:
        target = f"{item.base_path}-{suffix - shift}"
        clash = connection.execute("SELECT id FROM Content WHERE path = ?", (target,)).fetchone()
        if clash and clash[0] not in own:
            return f"{target} already exists and belongs to another row; left alone"

    if write:
        # One pass, no temporary names. The suffixes were just verified contiguous, so when
        # shifting DOWN the lowest target is free (nothing occupies a suffix below first_suffix)
        # and every later target was vacated by the move before it -- hence ascending order. An
        # item numbered from -0 shifts UP instead (shift < 0), where the same argument runs
        # backwards: the highest target is free and each earlier move is vacated by the one after
        # it, so the pass has to descend. Getting this direction wrong walks each row onto the one
        # ahead of it and fails on UNIQUE(path) after the collision checks above have passed --
        # which is what the "{base}-renumbering-{n}" parking pass this replaces existed to avoid.
        for row_id, suffix, _ in sorted(item.continuations, key=lambda entry: entry[1], reverse=shift < 0):
            connection.execute(
                # languageID too, for the same reason write_item normalises it: WebServer loads
                # continuations with "languageId = 1" hardcoded, so a renumbered row left under
                # another language is invisible and the page still truncates at its first 1 MiB.
                "UPDATE Content SET path = ?, languageID = ? WHERE id = ?",
                (f"{item.base_path}-{suffix - shift}", CONTINUATION_LANGUAGE_ID, row_id),
            )
    return ""


def report(errors: list[str], notes: list[str], limit: int = 30) -> None:
    """Print what went wrong and what merely deserves a look, to stderr, labelled."""
    for label, entries in (("error", errors), ("note", notes)):
        for entry in entries[:limit]:
            print(f"  {label}: {entry}", file=sys.stderr)
        if len(entries) > limit:
            print(f"  ... and {len(entries) - limit} more {label}s", file=sys.stderr)


def human(n: float) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(n) < 1024 or unit == "GiB":
            return f"{n:,.1f} {unit}" if unit != "B" else f"{n:,.0f} B"
        n /= 1024
    return f"{n:,.1f} GiB"


def phase_retype(connection, pool, args, write, items) -> tuple[set[str], list[str], list[str]]:
    """Retype rows that claim to be text but hold a recognisable binary payload.

    Returns (retyped paths, errors, notes). Errors are failures of the work this
    phase exists to do; notes are observations that do not make the run wrong.
    """
    print(f"[1/3] retype      {len(items):,} text-typed rows with a binary extension")
    if not items:
        return set(), [], []

    types = content_types(connection)
    counts: dict[str, int] = {}
    errors: list[str] = []
    notes: list[str] = []
    retyped: list[tuple[Item, Inspection, str]] = []
    before_total = after_total = 0

    # Batched like phase 3, and for the same reason: this holds every candidate's decoded
    # plaintext resident until the write loop, and one row in this database is 23 MB.
    inserted_total = deleted_total = 0
    kept: set[str] = set()

    # Each batch is inspected *and written* before the next one starts. Batching only the
    # submissions still held every decoded plaintext until a write loop at the end, so --batch
    # bounded the workers and not the memory, which is the thing that runs out.
    for offset in range(0, len(items), args.batch):
        pending = {}
        for item in items[offset : offset + args.batch]:
            blobs = read_blobs(connection, item)
            if blobs is None:
                errors.append(f"{item.base_path}: a row is missing or holds NULL content; left alone")
                continue
            pending[pool.submit(inspect_item, item, blobs)] = item

        for future in futures.as_completed(pending):
            item = pending[future]
            try:
                found = future.result()
            except Exception as exc:
                # A worker crash used to re-raise here and abort the phase with earlier
                # batches already committed and no summary. Report it like any other failure.
                errors.append(f"{item.base_path}: {type(exc).__name__}: {exc}")
                continue
            if found.status == "error":
                errors.append(f"{item.base_path}: {found.detail}")
                continue
            if found.status == "keep":
                counts["left as text"] = counts.get("left as text", 0) + 1
                notes.append(f"{item.base_path}: {found.detail}")
                continue

            target = found.sniffed
            if target == "video/quicktime" and args.mov_type == "mp4":
                target = "video/mp4"
            extension = item.base_path.rsplit(".", 1)[-1].lower()
            if extension not in target and not (extension in ("jpg", "jpeg") and target == "image/jpeg") \
                    and not (extension == "mov" and target.startswith("video/")):
                notes.append(f"{item.base_path}: named .{extension} but the payload is {found.sniffed}")

            if target not in types:
                if write:
                    cursor = connection.execute(
                        "INSERT INTO ContentTypes (value, compression) VALUES (?, 'none')", (value := target,)
                    )
                    types[value] = (cursor.lastrowid, "none")
                    print(f"      ContentTypes  + id {cursor.lastrowid} {value} (compression none)")
                else:
                    types[target] = (-1, "none")
                    print(f"      ContentTypes  would insert {target} (compression none)")

            type_id, compression = types[target]
            # A target type whose own compression is not 'none' cannot receive plaintext. Retyping
            # into it would leave the bytes compressed under a type they are not, which the verifier
            # then reports twice -- and if WebServer does not handle that compression at all, the row
            # serves raw compressed bytes to a browser.
            if compression != "none":
                # NOT "fix the ContentTypes row": that row is a shared dimension. In the shipped
                # database application/pdf, application/wasm, font/otf and font/ttf are all
                # registered brotli and all four extensions are in BINARY_EXTENSIONS, so following
                # that advice would set a legitimately compressed type to 'none' and make every
                # row of it -- every PDF in the Dynamic Bookshelf -- serve as raw compressed bytes.
                errors.append(
                    f"{item.base_path}: {target} is registered with compression '{compression}', not 'none'; "
                    f"left as {item.content_type}. Retype this row by hand, or exclude it with --path; "
                    f"do not change the ContentTypes row, which every other row of that type shares"
                )
                continue

            if write:
                try:
                    inserted, deleted = write_item(
                        connection, item, found.slices, renumber="renumber" in args.phase_list, content_type_id=type_id
                    )
                except PathClash as clash:
                    errors.append(str(clash))
                    found.slices = []
                    continue
                inserted_total += inserted
                deleted_total += deleted

            # Credited only once the write has actually happened. Crediting before it meant a row
            # whose write raised PathClash was still reported retyped, still had its bytes booked
            # into the plaintext totals, and was still handed to verify_retype -- which then read
            # the untouched row and emitted two more errors describing a disagreement that did not
            # exist, three errors for one failure.
            counts[target] = counts.get(target, 0) + 1
            before_total += found.before
            after_total += found.after
            kept.add(item.base_path)
            # found.slices is the only large thing here; dropping the reference lets this batch's
            # plaintext be collected before the next batch decodes its own.
            found.slices = []

        if write:
            connection.commit()

    for target, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"      {target:22} {count:>4}")
    print(f"      stored {human(before_total)} of Brotli -> {human(after_total)} of plaintext "
          f"({'+' if after_total >= before_total else ''}{human(after_total - before_total)})")
    if write:
        print(f"      rows inserted {inserted_total}   deleted {deleted_total}")
    return kept, errors, notes


def phase_renumber(connection, args, write, select) -> tuple[int, list[str], list[str]]:
    """Shift -2-based continuation numbering down to the -1 the app expects.

    [select] applies --limit and --path here as it does to the other phases. Without
    it a scoped trial run -- the first thing anyone sensibly tries -- silently
    rewrote every chunked item in the database.
    """
    items = select([item for item in load_items(connection, "1 = 1") if item.continuations])
    # load_items only groups a "-<digits>" sibling under a base holding exactly CHUNK_BYTES -- the
    # app's own chunk-detection rule -- so every item here is genuinely chunked, and a
    # coincidentally named independent page (e.g. k/kotlin-1-2 next to the real page k/kotlin-1)
    # never reaches the renames below.
    chunked = items
    broken = [item for item in chunked if item.first_suffix != 1]

    starts = sorted({item.first_suffix for item in broken})
    if broken:
        print(f"[2/3] renumber    {len(broken)} of {len(chunked)} chunked items start at "
              f"{', '.join('-' + str(n) for n in starts)} instead of -1")
    else:
        print(f"[2/3] renumber    all {len(chunked)} chunked items already start at -1")
    errors: list[str] = []
    notes: list[str] = []
    fixed = 0
    for item in broken:
        note = renumber_item(connection, item, write)
        if note:
            # A repair this phase exists to make and could not: an error, not an aside.
            errors.append(f"{item.base_path}: {note}")
        else:
            fixed += 1

    # An item already numbered from -1 never reaches renumber_item, so it never got the languageID
    # normalisation that lives there. WebServer's continuation query hardcodes "languageId = 1", so
    # a slice left under another language is invisible and the page truncates at its first 1 MiB --
    # the ADFA-5171 symptom, in an item this phase would otherwise report as healthy and pass to a
    # run that declares the dictionary version.
    relanguaged = 0
    for item in chunked:
        if item.first_suffix != 1 or not item.continuations:
            continue
        ids = [row_id for row_id, _, _ in item.continuations]
        stray = [
            row_id
            for (row_id,) in connection.execute(
                f"SELECT id FROM Content WHERE id IN ({','.join('?' * len(ids))}) AND languageID != ?",
                [*ids, CONTINUATION_LANGUAGE_ID],
            )
        ]
        if not stray:
            continue
        relanguaged += len(stray)
        notes.append(
            f"{item.base_path}: {len(stray)} continuation row(s) carried a languageID other than "
            f"{CONTINUATION_LANGUAGE_ID}, which hides them from the app's chunk query"
        )
        if write:
            connection.execute(
                f"UPDATE Content SET languageID = ? WHERE id IN ({','.join('?' * len(stray))})",
                [CONTINUATION_LANGUAGE_ID, *stray],
            )
    if relanguaged:
        print(f"      {'set' if write else 'would set'} languageID = {CONTINUATION_LANGUAGE_ID} on "
              f"{relanguaged} continuation row(s) that were hidden from the app")

    if write:
        connection.commit()

    if fixed:
        print(f"      {'renumbered' if write else 'would renumber'} to start at -1: {fixed}")
    return fixed, errors, notes


def verify_retype(connection, retyped_paths: set[str], mov_type: str, expect_renumbered: bool) -> list[str]:
    """Re-read what phase 1 wrote and confirm the bytes match the declared type."""
    problems: list[str] = []
    written = {item.base_path: item for item in load_items(connection, "1 = 1")}
    for path in sorted(retyped_paths):
        item = written.get(path)
        if item is None:
            problems.append(f"{path}: row vanished")
            continue
        blobs = read_blobs(connection, item)
        if blobs is None:
            problems.append(f"{path}: a row is missing or holds NULL content")
            continue
        payload = b"".join(blobs)
        found = sniff(payload)
        expected = item.content_type
        if expected == "video/mp4" and mov_type == "mp4" and found == "video/quicktime":
            found = "video/mp4"  # deliberately typed mp4; the container really is qt
        if found != expected:
            problems.append(f"{path}: declared {expected} but the stored bytes sniff as {found or 'unknown'}")
        if item.compression != "none":
            problems.append(f"{path}: retyped to {expected}, whose compression is {item.compression}")
        # Only when this run was asked to renumber: a retype-only run legitimately leaves
        # -2-based numbering alone, and reporting it as an error made a successful run exit 1.
        if expect_renumbered and item.continuations and item.suffixes != list(range(1, len(item.continuations) + 1)):
            problems.append(f"{path}: continuations numbered {item.suffixes}, expected 1..n")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("database", help="documentation.db to work on (operate on a copy)")
    parser.add_argument("--yes", action="store_true", help="actually write; without it the run is a dry run")
    parser.add_argument("--dry-run", action="store_true", help="explicit no-write run (the default anyway)")
    parser.add_argument("--phases", default=",".join(ALL_PHASES),
                        help=f"comma-separated subset of {','.join(ALL_PHASES)} (default: all, in that order)")
    parser.add_argument("--mov-type", choices=("quicktime", "mp4"), default="quicktime",
                        help="what to call the ftypqt .mov payloads: the honest video/quicktime (inserted into "
                             "ContentTypes) or the video/mp4 Chromium is likelier to play (default: quicktime)")
    parser.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2)), help="parallel compressors")
    parser.add_argument("--quality", type=int, default=11, help="brotli quality (default 11, as the pipeline uses)")
    parser.add_argument("--window", type=int, default=22,
                        help="brotli window log, 0 or 10-24 (default 22; the app's BrotliCompressor uses 24)")
    parser.add_argument("--limit", type=int, default=0, help="stop after this many items (for a smoke test)")
    parser.add_argument("--path", default="", help="only items whose base path contains this substring")
    parser.add_argument("--batch", type=int, default=200, help="items per write transaction")
    args = parser.parse_args()
    write = args.yes and not args.dry_run

    args.phase_list = [phase.strip() for phase in args.phases.split(",") if phase.strip()]
    unknown = [phase for phase in args.phase_list if phase not in ALL_PHASES]
    if unknown:
        print(f"error: unknown phase(s) {', '.join(unknown)}; pick from {', '.join(ALL_PHASES)}", file=sys.stderr)
        return 2
    # --phases "" and --phases "," both survived the comprehension as an empty list, whose
    # unknown-phase check iterates nothing: the run printed "mode WRITING", skipped the brotli and
    # CompressionDictionary preflight, executed no phase and exited 0. A CI step writing
    # --phases "$PHASES" with the variable unset reported a successful migration of a database it
    # had not touched, defeating the exit code this script goes to some trouble to make meaningful.
    if not args.phase_list:
        print(f"error: --phases selected no phases; pick from {', '.join(ALL_PHASES)}", file=sys.stderr)
        return 2

    # A run that may not declare the version may not write migrated content either -- the two
    # travel together or not at all. --path/--limit scope the run, so may_declare_version withholds
    # the declaration; phase 3 nevertheless recompressed those rows and COMMITTED them, printed a
    # WARNING on stdout and exited 0. Because the app gates the dictionary on the declared version
    # and not on the dictionary's presence, every row just rewritten decodes as "corrupt input",
    # and the plaintext it was rewritten from is gone. Refused here rather than warned about after
    # the damage. A scoped dry run is still useful and still allowed.
    withheld = may_declare_version(args)
    if write and "migrate" in args.phase_list and withheld:
        print(
            f"error: {withheld}, so this run may not declare database version "
            f"{DICTIONARY_MAJOR_VERSION} -- and content migrated without that declaration cannot be "
            f"decoded by the app at all. Re-run without --path/--limit to migrate, or drop --yes to "
            f"see what a scoped run would do.",
            file=sys.stderr,
        )
        return 2

    # range() raises on a zero batch, a negative one silently processes nothing, and
    # ProcessPoolExecutor raises on zero workers -- all after work may have started.
    if args.batch < 1:
        print(f"error: --batch must be at least 1, got {args.batch}", file=sys.stderr)
        return 2
    if args.workers < 1:
        print(f"error: --workers must be at least 1, got {args.workers}", file=sys.stderr)
        return 2
    # Same reason as the two above, and the same one-line shape: the brotli CLI rejects these, but
    # only per row inside a worker, so a typo spent a whole pass failing every item one subprocess
    # at a time -- and the run still declared the dictionary version at the end, leaving a database
    # claiming every brotli row was migrated when not one had been.
    if not 0 <= args.quality <= 11:
        print(f"error: --quality must be between 0 and 11, got {args.quality}", file=sys.stderr)
        return 2
    if args.window != 0 and not 10 <= args.window <= 24:
        print(f"error: --window must be 0 or between 10 and 24, got {args.window}", file=sys.stderr)
        return 2
    # Each worker holds roughly three copies of an item's plaintext (the decode, the dictionary
    # decode and the round-trip), and the largest chunked items here reach ~160 MB, so a high
    # worker count is an out-of-memory risk rather than a throughput win.
    if args.workers > MAX_WORKERS:
        print(f"error: --workers above {MAX_WORKERS} risks running the machine out of memory "
              f"(~3x an item's plaintext per worker, and the largest items here are ~160 MB), "
              f"got {args.workers}", file=sys.stderr)
        return 2

    connection = sqlite3.connect(args.database)
    connection.execute("PRAGMA foreign_keys = ON")

    # Only the phases that decode or encode need the dictionary. renumber only moves
    # paths, so requiring one there refused to run on exactly the old databases whose
    # numbering most needs repairing.
    dictionary = b""
    if any(phase in ("retype", "migrate") for phase in args.phase_list):
        # Fail before any phase runs, not per item inside a worker mid-run.
        if shutil.which("brotli") is None:
            print(
                "error: retype and migrate need the brotli CLI (>= 1.0) on PATH; "
                "no Python binding exposes custom dictionaries",
                file=sys.stderr,
            )
            return 2
        if not table_exists(connection, "CompressionDictionary"):
            print(
                "error: this database has no CompressionDictionary table, so there is nothing to "
                "migrate against; --phases renumber works without one",
                file=sys.stderr,
            )
            return 2
        dictionary_row = connection.execute("SELECT data FROM CompressionDictionary WHERE id = 1").fetchone()
        if dictionary_row is None or not dictionary_row[0]:
            print("error: this database has no CompressionDictionary row to migrate against", file=sys.stderr)
            return 2
        dictionary = dictionary_row[0]

    # write_item INSERTs continuations with languageID = CONTINUATION_LANGUAGE_ID, under
    # PRAGMA foreign_keys = ON. Its call sites catch PathClash only, so on a database that numbers
    # its languages differently the FK violation propagated out of main() from the middle of a
    # phase, with earlier batches already committed and no summary printed. One query, up front.
    if write and table_exists(connection, "Languages"):
        if connection.execute(
            "SELECT 1 FROM Languages WHERE id = ?", (CONTINUATION_LANGUAGE_ID,)
        ).fetchone() is None:
            print(
                f"error: this database's Languages table has no id {CONTINUATION_LANGUAGE_ID}, but the "
                f"app's continuation query hardcodes that id, so every slice this script writes would "
                f"be invisible (and rejected by the foreign key). Nothing written.",
                file=sys.stderr,
            )
            connection.close()
            return 2

    def select(items: list[Item]) -> list[Item]:
        if args.path:
            items = [item for item in items if args.path in item.base_path]
        return items[: args.limit] if args.limit else items

    print(f"database        {args.database}")
    print(f"dictionary      {human(len(dictionary)) if dictionary else 'not needed for these phases'}")
    print(f"phases          {' -> '.join(args.phase_list)}")
    print(f"workers         {args.workers}   quality {args.quality}   window {args.window}")
    print(f"mode            {'WRITING' if write else 'dry run (pass --yes to write)'}")
    print()

    errors: list[str] = []
    notes: list[str] = []
    retyped_paths: set[str] = set()
    started = time.time()

    # Phase 3's tallies live out here, not next to its loop, because the summary below has to be
    # printable whether or not that loop ran or finished -- see the abort handling after the try.
    counts = {"migrated": 0, "already": 0, "error": 0}
    wrote_migrated_content = False
    version_declared = False
    migrate_started = False
    before_total = after_total = 0
    inserted_total = deleted_total = 0
    # Not named `errors`: that name already holds this run's phase 1 and 2 failures,
    # and reusing it here would discard them.
    failed_items: list[Result] = []
    mislabelled: list[Result] = []
    aborted = ""

    # Not a `with`: on the way out of one, ProcessPoolExecutor.shutdown waits for every future it
    # has already queued, so a Ctrl-C hung on the work it was trying to abandon. Built explicitly
    # so the finally can cancel instead.
    pool = futures.ProcessPoolExecutor(args.workers, initializer=_init_worker, initargs=(dictionary,))
    try:
        if "retype" in args.phase_list:
            candidates = select([
                item for item in load_items(connection, "(CT.value = 'text' OR CT.value LIKE 'text/%')")
                if item.base_path.lower().endswith(BINARY_EXTENSIONS)
            ])
            retyped_paths, phase_errors, phase_notes = phase_retype(connection, pool, args, write, candidates)
            errors += phase_errors
            notes += phase_notes
            if write:
                # A verification failure means the bytes and their declared type disagree
                # after we wrote them -- the most serious thing this script can report.
                errors += verify_retype(
                    connection, retyped_paths, args.mov_type, expect_renumbered="renumber" in args.phase_list
                )
            print()

        if "renumber" in args.phase_list:
            _, phase_errors, phase_notes = phase_renumber(connection, args, write, select)
            errors += phase_errors
            notes += phase_notes
            print()

        if "migrate" not in args.phase_list:
            connection.commit() if write else connection.rollback()
            connection.close()
            sys.stdout.flush()
            report(errors, notes)
            # Non-zero when something the run set out to do did not happen: this path
            # used to return 0 whatever it had just printed, so a wrapper script or CI
            # step could not tell a clean repair from a failed one.
            return 1 if errors else 0

        items = select(load_items(connection, "CT.compression = 'brotli'"))
        chunked = [item for item in items if item.continuations]

        print(f"[3/3] migrate     {len(items):,} items ({len(chunked)} of them stored as multiple slices)")
        print(f"      stored now  {human(sum(item.stored_bytes for item in items))}")
        print()

        migrate_started = True

        # Snapshotted BEFORE the first batch declares anything. This run declares the dictionary
        # version as soon as it commits migrated content, so reading the declaration per item would
        # flip the answer halfway through and start treating the still-plain rows as migrated.
        starting_major = declared_major(connection)
        db_was_migrated = starting_major is not None and starting_major >= DICTIONARY_MAJOR_VERSION

        for offset in range(0, len(items), args.batch):
            batch = items[offset : offset + args.batch]
            pending = {}
            for item in batch:
                blobs = read_blobs(connection, item)
                if blobs is None:
                    # Reported, not raised: this used to surface as a TypeError inside a worker
                    # and abort the run with earlier batches already committed and no summary.
                    counts["error"] += 1
                    failed_items.append(
                        Result(item.base_path, "error", detail="a row is missing or holds NULL content")
                    )
                    continue
                pending[
                    pool.submit(
                        migrate_item, item, blobs, args.quality, args.window, db_was_migrated,
                    )
                ] = item

            for future in futures.as_completed(pending):
                item = pending[future]
                try:
                    result = future.result()
                except Exception as exc:
                    # Same reason as the read_blobs guard above: report, do not abort a run
                    # whose earlier batches are already committed.
                    counts["error"] += 1
                    failed_items.append(
                        Result(item.base_path, "error", detail=f"{type(exc).__name__}: {exc}")
                    )
                    continue
                if result.sniffed:
                    mislabelled.append(result)

                # The write can still fail, so nothing is credited until it has not. Counting
                # first tallied a PathClash as "migrated" and subtracted its never-written bytes
                # from the reported savings while counts["error"] stayed 0.
                if result.status == "migrated" and write:
                    try:
                        inserted, deleted = write_item(connection, item, result.slices, renumber=False)
                    except (PathClash, sqlite3.IntegrityError) as clash:
                        counts["error"] += 1
                        # PathClash already names the item, and the failed_items loop prefixes the
                        # path again when it prints. Strip it here, and report through failed_items
                        # alone -- appending to `errors` too printed the same failure twice.
                        detail, prefix = str(clash), f"{item.base_path}: "
                        result.detail = detail[len(prefix):] if detail.startswith(prefix) else detail
                        failed_items.append(result)
                        result.slices = []
                        continue
                    inserted_total += inserted
                    deleted_total += deleted
                    wrote_migrated_content = True

                counts[result.status] += 1
                before_total += result.before
                after_total += result.after or result.before
                if result.status == "error":
                    failed_items.append(result)
                # Same reason as phase 1: a completed Future holds its Result, and pending keeps
                # every Future in the batch, so without this a batch of recompressed payloads stays
                # resident while the next batch reads its own. Outside the write branch it used to be
                # indented into, because a dry run builds exactly the same payloads and never
                # released them: 195,136 KB peak RSS against 117,888 KB, measured on 40 incompressible
                # 2 MiB rows at --batch 40 with the only difference being this line's indentation.
                result.slices = []

            if write:
                # In the same transaction as the first batch of migrated content, not after the last
                # one. A run interrupted between two committed batches would otherwise leave the
                # database holding dictionary-compressed rows while declaring a version below the one
                # the app requires -- so it would decline the dictionary and every committed row
                # would fail to decode. Declaring first means the worst an interruption leaves is a
                # partly migrated database that still serves, since the app falls back to a plain
                # decode per row.
                if wrote_migrated_content and not version_declared and not may_declare_version(args):
                    before = declared_major(connection)
                    if before is None or before < DICTIONARY_MAJOR_VERSION:
                        declare_dictionary_version(connection)
                        print(f"\ndeclared        database version {DICTIONARY_MAJOR_VERSION}.0.0 "
                              f"(was {'none' if before is None else before})")
                    version_declared = True
                connection.commit()

            done = min(offset + args.batch, len(items))
            elapsed = time.time() - started
            rate = done / elapsed if elapsed else 0
            remaining = (len(items) - done) / rate if rate else 0
            print(
                f"\r      {done:,}/{len(items):,} items  {rate:5.1f}/s  "
                f"eta {remaining/60:4.1f} min  saved {human(before_total - after_total)}",
                end="",
                flush=True,
            )

    # An abort must not skip the summary. Every one of these used to escape main() as a traceback,
    # past both end-of-run version warnings, past report() and past the exit code -- leaving a
    # database that had already declared the dictionary version with most of its rows still plain
    # brotli, and nothing on screen connecting the two. Ctrl-C is how a multi-hour run most often
    # ends, so this is the common case, not the exotic one.
    except KeyboardInterrupt:
        aborted = "interrupted with Ctrl-C"
    except futures.process.BrokenProcessPool:
        aborted = "a worker process died (killed by the OOM killer, most likely)"
    except sqlite3.Error as exc:
        aborted = f"the database rejected an operation: {type(exc).__name__}: {exc}"
    finally:
        pool.shutdown(wait=False, cancel_futures=True)

    print("\n")
    if aborted:
        print(f"ABORTED         {aborted}")
        print("                Committed batches are kept; what follows describes the work that")
        print("                had finished when the run stopped.")
    if aborted and not migrate_started:
        print("                The migrate phase had not started.")
    print(f"migrated        {counts['migrated']:,}")
    print(f"already         {counts['already']:,}")
    print(f"errors          {counts['error']:,}")
    if write:
        print(f"rows inserted   {inserted_total}    rows deleted    {deleted_total}")
    print(f"stored before   {human(before_total)}")
    print(f"stored after    {human(after_total)}")
    if before_total:
        print(f"saved           {human(before_total - after_total)} ({100 * (before_total - after_total) / before_total:.1f}%)")
    print(f"took            {(time.time() - started)/60:.1f} min")

    if mislabelled:
        print(f"\nstill text-typed but holding binary ({len(mislabelled)}; phase 1 nominates by extension only):")
        for result in mislabelled[:20]:
            print(f"  {result.base_path}: {result.sniffed}")
        if len(mislabelled) > 20:
            print(f"  ... and {len(mislabelled) - 20} more")

    for result in failed_items[:20]:
        print(f"  error: {result.base_path}: {result.detail}", file=sys.stderr)
    if len(failed_items) > 20:
        print(f"  ... and {len(failed_items) - 20} more", file=sys.stderr)
    sys.stdout.flush()
    report(errors, notes)

    if write:
        # The version goes in with the content, not after it: a database holding
        # dictionary-compressed rows while declaring anything below
        # DICTIONARY_MAJOR_VERSION is one the app refuses to attach the dictionary for, so every
        # row just migrated fails to decode. Declared even on a partly failed run -- WebServer
        # tries the dictionary first and falls back to a plain decode, so a row that did not
        # migrate still serves, while the ones that did only serve with this row present.
        # Anything already dictionary-compressed still needs the declaration, even when this run
        # migrated nothing itself (every row came back "already").
        before = declared_major(connection)
        withheld = may_declare_version(args)
        if withheld and (before is None or before < DICTIONARY_MAJOR_VERSION):
            print(f"\nWARNING         did NOT declare database version {DICTIONARY_MAJOR_VERSION}.0.0: {withheld}.")
            print("                The declaration covers the whole database, so only an unscoped run may make")
            print("                it. Re-run without --path/--limit before shipping this database.")
        if not version_declared and not withheld and (before is None or before < DICTIONARY_MAJOR_VERSION):
            declare_dictionary_version(connection)
            print(f"declared        database version {DICTIONARY_MAJOR_VERSION}.0.0 "
                  f"(was {'none' if before is None else before})")
            version_declared = True
        connection.commit()
        # Attaching the dictionary to a plain-compressed row usually throws (the app then falls
        # back to a plain decode), but a small fraction decode without error to different bytes.
        # So a declared database still holding plain rows is not merely incomplete: it can serve
        # silently wrong content. counts["error"] misses items that failed at the write (PathClash)
        # after counting as migrated; failed_items holds both, so it is the honest tally.
        # Rows the run never reached count too. An abort leaves most of them unattempted, and they
        # are exactly as exposed as a row that failed: plain brotli in a database declaring the
        # dictionary version.
        attempted = sum(counts.values())
        unattempted = max(0, len(items) - attempted) if migrate_started else 0
        remaining = len(failed_items) + unattempted
        if version_declared and remaining:
            print(
                f"\nWARNING: this database declares version {DICTIONARY_MAJOR_VERSION}.x but "
                f"{remaining} brotli item(s) did not migrate and are still stored as before. "
                f"A plain-compressed row can decode against the dictionary to wrong bytes "
                f"without erroring, so re-run this script to completion before shipping "
                f"this database.",
                file=sys.stderr,
            )
        print("\nRun VACUUM to reclaim the freed pages:  sqlite3 %s 'VACUUM;'" % args.database)
    else:
        connection.rollback()
        before = declared_major(connection)
        if before is None or before < DICTIONARY_MAJOR_VERSION:
            print(f"would declare   database version {DICTIONARY_MAJOR_VERSION}.0.0 "
                  f"(currently {'none' if before is None else before}) -- without it the app will not "
                  f"attach the dictionary and every migrated row fails to decode")
        print("\nNothing written. Re-run with --yes on a copy to apply.")

    connection.close()
    return 1 if aborted or errors or failed_items else 0


if __name__ == "__main__":
    sys.exit(main())

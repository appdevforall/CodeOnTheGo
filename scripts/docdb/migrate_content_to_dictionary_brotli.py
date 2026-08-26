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
                 falls back to a plain one, so a half-migrated database still
                 serves -- which is what makes running this incrementally safe.

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

  * A few rows decode identically with and without the dictionary: tiny,
    already-compressed payloads where the compressor found nothing to reference.
    Those are left alone, so "already migrated" covers them as well as genuinely
    dictionary-bound rows, and re-running does not churn them.

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
import sqlite3
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field

CHUNK_BYTES = 1024 * 1024

# WebServer looks continuations up with "languageId = 1" hardcoded, whatever the base row says.
CONTINUATION_LANGUAGE_ID = 1


def is_text_type(value: str) -> bool:
    """Whether a ContentTypes.value is a text type, matched at the boundary.

    `startswith("text")` also matches `textual/example`; the database's bare `text`
    oddity is why the exact match is needed alongside `text/`. Same rule as the
    app's ContentTypeHeaders (ADFA-5241).
    """
    return value == "text" or value.startswith("text/")
CONTINUATION = re.compile(r"^(.*)-(\d+)$")
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
    status: str  # migrated | already | unchanged | error
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
        ok, payload = decode_plain(stored)
        if not ok:
            ok, payload = decode_with_dictionary(stored)
            if not ok:
                return Inspection(item.base_path, "error", before=before,
                                  detail="decodes neither plainly nor with the dictionary")
    else:
        payload = stored

    kind = sniff(payload)
    if not kind:
        return Inspection(item.base_path, "keep", before=before, after=before,
                          detail=f"declared {item.content_type}, and the payload carries no binary signature")

    return Inspection(item.base_path, "retype", sniffed=kind, slices=slice_stream(payload),
                      before=before, after=len(payload))


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

    # Decoding every row here anyway makes a mislabel sweep free: report a
    # text-typed row whose payload is recognisably binary, whatever its name.
    mislabelled = sniff(plaintext) if is_text_type(item.content_type) else ""

    # A stream that decodes *both* ways is one the compressor never referenced the
    # dictionary for -- small, already-compressed payloads like a 1 KB GIF. It is
    # byte-identical in either form, so there is nothing to migrate, and skipping it
    # keeps a re-run from recompressing it for no gain.
    ok_dict, as_dict = decode_with_dictionary(stored)
    if ok_dict and as_dict == plaintext:
        return Result(item.base_path, "already", before=before, after=before, sniffed=mislabelled)

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
        return Result(item.base_path, "unchanged", before=before, after=before, sniffed=mislabelled,
                      detail=f"dictionary-compressed form is larger ({len(recompressed)} vs {before})")

    return Result(item.base_path, "migrated", slices=slice_stream(recompressed),
                  before=before, after=len(recompressed), sniffed=mislabelled)


def load_items(connection: sqlite3.Connection, predicate: str) -> list[Item]:
    rows = connection.execute(
        f"""
        SELECT C.id, C.path, C.languageID, C.contentTypeID, C.templateId,
               -- IFNULL: a row with NULL content has NULL length, which made the byte totals
               -- (and so the phase summary) throw before read_blobs could report the row.
               IFNULL(LENGTH(C.content), 0), CT.value, CT.compression
          FROM Content C
          JOIN ContentTypes CT ON CT.id = C.contentTypeID
         WHERE {predicate}
        """
    ).fetchall()

    base_bytes_by_path = {row[1]: row[5] for row in rows}
    items: dict[str, Item] = {}
    continuations: list[tuple[str, int, int, int]] = []

    for row_id, path, language_id, type_id, template_id, length, type_value, compression in rows:
        match = CONTINUATION.match(path)
        # A "-<digits>" sibling is a naming coincidence until the base row proves otherwise, and
        # the proof is the base holding exactly CHUNK_BYTES -- the app's own chunk-detection rule.
        # Grouping on the name alone let a rewrite of the greedy base (retype or migrate) absorb an
        # independent page's bytes and delete its row; here every phase inherits the test.
        if match and base_bytes_by_path.get(match.group(1)) == CHUNK_BYTES:
            continuations.append((match.group(1), row_id, int(match.group(2)), length))
        else:
            items[path] = Item(
                base_path=path,
                base_id=row_id,
                language_id=language_id,
                content_type_id=type_id,
                template_id=template_id,
                content_type=type_value,
                compression=compression,
                base_bytes=length,
            )

    orphans: list[tuple[str, int, int, int]] = []
    for base_path, row_id, suffix, length in continuations:
        owner = items.get(base_path)
        if owner is not None:
            owner.continuations.append((row_id, suffix, length))
        else:
            # The greedy base is itself a continuation, or the phase predicate excluded it, so this
            # row has no owner to be a slice of. Silently dropping it meant a row that was never
            # migrated, never counted and never reported -- in a database the run then declares
            # version 2.
            orphans.append((base_path, row_id, suffix, length))

    for item in items.values():
        item.continuations.sort(key=lambda entry: entry[1])

    # A base of exactly CHUNK_BYTES is not enough on its own. A genuine slice set has every slice
    # except the last at exactly CHUNK_BYTES, because that is how the writer splits; an 11-byte "-2"
    # followed by a "-3" is provably not one. Without this, a real page that happens to be exactly
    # 1 MiB, sitting next to independently named "-2"/"-3" pages, was grouped with them and phase 2
    # renamed those pages into its slice slots -- both URLs 404, and the app appends a foreign page's
    # bytes on reassembly. Verified against the real schema before and after this check.
    for item in list(items.values()):
        if not item.continuations:
            continue
        head = item.continuations[:-1]
        if all(length == CHUNK_BYTES for _, _, length in head):
            continue
        for row_id, suffix, length in item.continuations:
            path = f"{item.base_path}-{suffix}"
            items[path] = Item(
                base_path=path,
                base_id=row_id,
                language_id=item.language_id,
                content_type_id=item.content_type_id,
                template_id=item.template_id,
                content_type=item.content_type,
                compression=item.compression,
                base_bytes=length,
            )
        item.continuations.clear()

    if orphans:
        for base_path, _, suffix, _ in orphans:
            print(
                f"      note: {base_path}-{suffix} looks like a continuation of {base_path}, which is "
                f"not itself a migratable row; left alone and not migrated"
            )

    return sorted(items.values(), key=lambda item: item.base_path)


def read_blobs(connection: sqlite3.Connection, item: Item) -> list[bytes] | None:
    """An item's slices in order, or None when a row has vanished or holds NULL content.

    None rather than an exception: a single unreadable row used to abort the whole run from
    inside a worker, leaving earlier batches committed and printing no summary at all.
    """
    ids = [item.base_id] + [row_id for row_id, _, _ in item.continuations]
    placeholders = ",".join("?" * len(ids))
    found = dict(connection.execute(f"SELECT id, content FROM Content WHERE id IN ({placeholders})", ids).fetchall())
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


def retype_rows(connection: sqlite3.Connection, item: Item, content_type_id: int) -> None:
    """Point an item's rows at a different content type, leaving the bytes alone."""
    ids = [item.base_id] + [row_id for row_id, _, _ in item.continuations]
    placeholders = ",".join("?" * len(ids))
    connection.execute(
        f"UPDATE Content SET contentTypeID = ? WHERE id IN ({placeholders})",
        [content_type_id, *ids],
    )


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
    """Shift an item's continuations down so they start at -1. Returns a note, or ''."""
    shift = item.first_suffix - 1
    if shift <= 0:
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
        # One ascending pass, no temporary names. The suffixes were just verified
        # contiguous, so the lowest target (first_suffix - shift) is free -- nothing
        # occupies a suffix below first_suffix -- and every later target was vacated by
        # the move before it. The parking pass this replaces invented
        # "{base}-renumbering-{n}" paths that a real row could already hold, which would
        # fail on UNIQUE(path) after the collision checks above had passed.
        for row_id, suffix, _ in sorted(item.continuations, key=lambda entry: entry[1]):
            connection.execute(
                "UPDATE Content SET path = ? WHERE id = ?",
                (f"{item.base_path}-{suffix - shift}", row_id),
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
            found = future.result()
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
                errors.append(
                    f"{item.base_path}: {target} is registered with compression '{compression}', not 'none'; "
                    f"left as {item.content_type}. Fix the ContentTypes row, then re-run"
                )
                continue

            counts[target] = counts.get(target, 0) + 1
            before_total += found.before
            after_total += found.after
            kept.add(item.base_path)

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


def phase_renumber(connection, args, write, retyped_paths: set[str], select) -> tuple[int, list[str], list[str]]:
    """Shift -2-based continuation numbering down to the -1 the app expects.

    [select] applies --limit and --path here as it does to the other phases. Without
    it a scoped trial run -- the first thing anyone sensibly tries -- silently
    rewrote every chunked item in the database.
    """
    items = select([item for item in load_items(connection, "1 = 1") if item.continuations])
    if args.renumber_scope == "retyped":
        items = [item for item in items if item.base_path in retyped_paths]
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
        payload = b"".join(read_blobs(connection, item))
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
    parser.add_argument("--renumber-scope", choices=("all", "retyped"), default="all",
                        help="renumber every -2-based chunked item, or only the ones phase 1 retyped (default: all)")
    parser.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2)), help="parallel compressors")
    parser.add_argument("--quality", type=int, default=11, help="brotli quality (default 11, as the pipeline uses)")
    parser.add_argument("--window", type=int, default=22, help="brotli window log (default 22, the portable maximum)")
    parser.add_argument("--limit", type=int, default=0, help="stop after this many items (for a smoke test)")
    parser.add_argument("--path", default="", help="only items whose base path contains this substring")
    parser.add_argument("--batch", type=int, default=200, help="items per write transaction")
    parser.add_argument(
        "--only-if-smaller",
        action="store_true",
        help="leave a row alone when its dictionary-compressed form is not smaller "
             "(refused when the migrate phase runs: see the error it prints)",
    )
    args = parser.parse_args()
    write = args.yes and not args.dry_run

    args.phase_list = [phase.strip() for phase in args.phases.split(",") if phase.strip()]
    unknown = [phase for phase in args.phase_list if phase not in ALL_PHASES]
    if unknown:
        print(f"error: unknown phase(s) {', '.join(unknown)}; pick from {', '.join(ALL_PHASES)}", file=sys.stderr)
        return 2

    # A migrate run declares version DICTIONARY_MAJOR_VERSION, and a plain-brotli row left behind
    # in a database declaring that version can decode against the dictionary to different bytes
    # *without erroring* -- served as silently wrong content. --only-if-smaller deliberately
    # leaves such rows, so the two cannot travel together.
    if args.only_if_smaller and "migrate" in args.phase_list:
        print(
            "error: --only-if-smaller cannot be combined with the migrate phase: it deliberately "
            "leaves rows plain-compressed in a database the run declares version "
            f"{DICTIONARY_MAJOR_VERSION}, and a plain row in such a database can decode against "
            "the dictionary to wrong bytes without erroring",
            file=sys.stderr,
        )
        return 2

    connection = sqlite3.connect(args.database)
    connection.execute("PRAGMA foreign_keys = ON")

    # Only the phases that decode or encode need the dictionary. renumber only moves
    # paths, so requiring one there refused to run on exactly the old databases whose
    # numbering most needs repairing.
    dictionary = b""
    if any(phase in ("retype", "migrate") for phase in args.phase_list):
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

    with futures.ProcessPoolExecutor(args.workers, initializer=_init_worker, initargs=(dictionary,)) as pool:
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
            _, phase_errors, phase_notes = phase_renumber(connection, args, write, retyped_paths, select)
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

        counts = {"migrated": 0, "already": 0, "unchanged": 0, "error": 0}
        wrote_migrated_content = False
        version_declared = False
        before_total = after_total = 0
        inserted_total = deleted_total = 0
        # Not named `errors`: that name already holds this run's phase 1 and 2 failures,
        # and reusing it here would discard them.
        failed_items: list[Result] = []
        mislabelled: list[Result] = []

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
                        migrate_item, item, blobs, args.quality, args.window, args.only_if_smaller
                    )
                ] = item

            for future in futures.as_completed(pending):
                item = pending[future]
                result = future.result()
                counts[result.status] += 1
                before_total += result.before
                after_total += result.after or result.before

                if result.sniffed:
                    mislabelled.append(result)
                if result.status == "error":
                    failed_items.append(result)
                elif result.status == "migrated" and write:
                    try:
                        inserted, deleted = write_item(connection, item, result.slices, renumber=False)
                    except PathClash as clash:
                        failed_items.append(result)
                        errors.append(str(clash))
                        result.slices = []
                        continue
                    inserted_total += inserted
                    deleted_total += deleted
                    wrote_migrated_content = True
                    # Same reason as phase 1: a completed Future holds its Result, and pending keeps
                    # every Future in the batch, so without this a batch of recompressed payloads
                    # stays resident while the next batch reads its own.
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
        remaining = counts["unchanged"] + len(failed_items)
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
    return 1 if errors or failed_items else 0


if __name__ == "__main__":
    sys.exit(main())

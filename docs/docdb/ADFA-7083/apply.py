#!/usr/bin/env python3
"""Adds the ADFA-7083 pages to a documentation.db.

Usage: python3 apply.py DOCUMENTATION_DB

Builds the pages (build.py), compresses each with Brotli against the
database's CompressionDictionary (quality 11, lgwin 24, as the documentation
pipeline does), and in ONE transaction:
  - upserts a Content row per page, path i/<file>, templateId of
    helpmanual-topic.peb, text/html, EN-us;
  - appends a link to the new pages to i/terminal-top.html, unless it has one;
  - records the change in LastChange (content-i and wholedb).
Any failure rolls everything back. Safe to run again: it replaces its rows.

Needs the `brotli` command-line tool. Run it on a copy first: this edits the
file in place, and the master lives in OfflineDocumentationTools' workflow.
"""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
LINK_FILE = "terminal-top.html"
LINK_TARGET = "sqlite-js-top.html"
LINK_HTML = ('<p class="p_Normal">To write SQL functions in JavaScript, see '
             '<a href="sqlite-js-top.html" class="topiclink">JavaScript in SQLite (sqlite-js)</a>.</p>\r\n')
WHO = "ADFA-7083"


def brotli(args, data, workdir):
    src, dst = os.path.join(workdir, "in"), os.path.join(workdir, "out")
    for f in (dst,):
        if os.path.exists(f):
            os.remove(f)
    with open(src, "wb") as f:
        f.write(data)
    subprocess.run(["brotli", "-f", "-o", dst] + args + [src], check=True)
    with open(dst, "rb") as f:
        out = f.read()
    if not out:
        raise RuntimeError("brotli produced no output")
    return out


def main():
    db_path = sys.argv[1]
    if not shutil.which("brotli"):
        sys.exit("the brotli command-line tool is required")
    work = tempfile.mkdtemp(prefix="adfa7083-")  # mode 700
    try:
        out_dir = os.path.join(work, "pages")
        subprocess.run([sys.executable, os.path.join(HERE, "build.py"), out_dir], check=True)

        db = sqlite3.connect(db_path, isolation_level=None)
        db.execute("PRAGMA foreign_keys = ON")
        template = db.execute("SELECT id FROM Templates WHERE name = 'helpmanual-topic.peb'").fetchone()
        html_type = db.execute("SELECT id FROM ContentTypes WHERE value = 'text/html'").fetchone()
        lang = db.execute("SELECT id FROM Languages WHERE lower(value) = 'en-us'").fetchone()
        dictionary = db.execute("SELECT data FROM CompressionDictionary WHERE id = 1").fetchone()
        if not (template and html_type and lang and dictionary):
            sys.exit("this does not look like a documentation.db: a template, content type, language or "
                     "dictionary is missing")
        dict_path = os.path.join(work, "dict")
        with open(dict_path, "wb") as f:
            f.write(dictionary[0])

        db.execute("BEGIN IMMEDIATE")
        try:
            for name in sorted(os.listdir(out_dir)):
                with open(os.path.join(out_dir, name), "rb") as f:
                    data = f.read()
                blob = brotli(["-q", "11", "-w", "24", "-D", dict_path], data, work)
                db.execute(
                    "INSERT INTO Content (path, languageID, content, contentTypeID, templateId) "
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT(path) DO UPDATE SET "
                    "languageID = excluded.languageID, content = excluded.content, "
                    "contentTypeID = excluded.contentTypeID, templateId = excluded.templateId",
                    ("i/" + name[:-len(".json")], lang[0], blob, html_type[0], template[0]))
                print("upserted i/%s (%d bytes)" % (name[:-len(".json")], len(blob)))

            row = db.execute("SELECT content FROM Content WHERE path = ?", ("i/" + LINK_FILE,)).fetchone()
            if row is None:
                raise RuntimeError("i/%s is not in the database" % LINK_FILE)
            page = json.loads(brotli(["-d", "-D", dict_path], row[0], work))
            if LINK_TARGET in page["hmBody"]:
                print("i/%s already links to %s" % (LINK_FILE, LINK_TARGET))
            else:
                page["hmBody"] += LINK_HTML
                blob = brotli(["-q", "11", "-w", "24", "-D", dict_path],
                              json.dumps(page, ensure_ascii=False).encode("utf-8"), work)
                db.execute("UPDATE Content SET content = ? WHERE path = ?", (blob, "i/" + LINK_FILE))
                print("added a link to i/%s" % LINK_FILE)

            for doc_set in ("content-i", "wholedb"):
                db.execute("INSERT INTO LastChange (documentationSet, who) VALUES (?, ?)", (doc_set, WHO))
            db.execute("COMMIT")
        except BaseException:
            db.execute("ROLLBACK")
            raise
        db.close()
    finally:
        shutil.rmtree(work, ignore_errors=True)


main()

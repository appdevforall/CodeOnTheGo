#!/usr/bin/env python3
"""Adds the ADFA-7083 pages to a documentation.db.

Usage: python3 apply.py DOCUMENTATION_DB

Builds the pages (build.py), compresses each with Brotli against the
database's CompressionDictionary (quality 11, lgwin 24, as the documentation
pipeline does), and in ONE transaction:
  - upserts a Content row per page whose row is missing or differs, path
    i/<file>, templateId of helpmanual-topic.peb, text/html, EN-us;
  - appends a section that links to the new pages to i/terminal-top.html,
    unless it has one. The page's other sections start collapsed, so the link
    gets a heading of its own, where readers see it. The page is either a
    template context (JSON, rendered by helpmanual-topic.peb), as in databases
    up to wholedb 2026-10-16, or a finished Help+Manual page (HTML), as from
    wholedb 2026-11-02; the section goes at the end of the topic's body in both.
    A database of the second kind also stores the page's i/jstopics/
    terminal-top.js, which the help viewer loads to move between pages, so the
    section goes into that file's hmBody too;
  - if a page or the link changed, stamps the content-i and wholedb rows of
    LastChange, inserting any that is missing, as docdb-studio does.
Any failure rolls everything back. A second run changes nothing.

It refuses a database that does not declare version 2 or later: the app
decodes rows with the dictionary only from version 2.

Needs the `brotli` command-line tool. Run it on a copy first: this edits the
file in place, and the master lives in OfflineDocumentationTools' workflow.
"""
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
LINK_FILE = "terminal-top.html"
LINK_TARGET = "sqlite-js-top.html"
LINK_HTML = ('<h1 class="p_Heading1" style="page-break-after: avoid;"><a id="sqlite-js" class="hmanchor"></a>'
             '<span class="f_Heading1">SQL functions in JavaScript</span></h1>\r\n'
             '<p class="p_Normal">To write SQL functions in JavaScript, see '
             '<a href="sqlite-js-top.html" class="topiclink">JavaScript in SQLite (sqlite-js)</a>.</p>\r\n')
WHO = "ADFA-7083"
# A finished page's topic body ends where the skin closes its scroller div.
HTML_BODY_END = re.compile(r"\n\s*</div>\s*<!-- hmpagebody_scroller -->")
# A stored jstopics file is hmLoadTopic({...}) whose last property is the hmBody string.
JSTOPIC_BODY_END = re.compile(r'"\s*\}\)\s*$')


def add_link(text):
    """The page with the link section added, or None if it already has it."""
    if text.lstrip("\ufeff \t\r\n").startswith("{"):
        page = json.loads(text)
        if 'id="sqlite-js"' in page["hmBody"]:
            return None
        page["hmBody"] += LINK_HTML
        return json.dumps(page, ensure_ascii=False)
    if 'id="sqlite-js"' in text:
        return None
    ends = list(HTML_BODY_END.finditer(text))
    if len(ends) != 1:
        raise RuntimeError("i/%s is HTML, but its topic body's end was found %d times, not once"
                           % (LINK_FILE, len(ends)))
    at = ends[0].start()
    return text[:at] + "\n" + LINK_HTML.replace("\r\n", "\n").rstrip("\n") + text[at:]


def add_link_to_jstopic(text):
    """The jstopics file with the link section added to its hmBody, or None if it has it."""
    if 'id=\\"sqlite-js\\"' in text:
        return None
    ends = list(JSTOPIC_BODY_END.finditer(text))
    body = text.rfind("hmBody:")
    if len(ends) != 1 or body < 0 or re.search(r"\n\s*hm\w+:", text[body:]):
        raise RuntimeError("i/jstopics/%s does not end with its hmBody string" % LINK_FILE.replace(".html", ".js"))
    # escaped as Help+Manual writes these strings: \" for quotes, <\/ for </, \n\r at line ends
    escaped = (LINK_HTML.replace("\\", "\\\\").replace('"', '\\"').replace("</", "<\\/")
               .replace("\r\n", "\\n\\r"))
    at = ends[0].start()
    return text[:at] + escaped + text[at:]


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
        # The app's own query and order (DatabaseVersionResolver): the newest row's version counts.
        try:
            version = db.execute("SELECT major FROM DocumentationDatabaseVersion "
                                 "ORDER BY changeTime DESC, rowid DESC LIMIT 1").fetchone()
        except sqlite3.OperationalError:
            version = None
        if not version or version[0] < 2:
            sys.exit("this database does not declare version 2 or later, so the app would not decode "
                     "pages compressed against its dictionary")
        dict_path = os.path.join(work, "dict")
        with open(dict_path, "wb") as f:
            f.write(dictionary[0])

        db.execute("BEGIN IMMEDIATE")
        try:
            changed = False
            for name in sorted(os.listdir(out_dir)):
                with open(os.path.join(out_dir, name), "rb") as f:
                    data = f.read()
                path = "i/" + name[:-len(".json")]
                blob = brotli(["-q", "11", "-w", "24", "-D", dict_path], data, work)
                stored = db.execute("SELECT languageID, content, contentTypeID, templateId FROM Content "
                                    "WHERE path = ?", (path,)).fetchone()
                if stored and (stored[0], bytes(stored[1]), stored[2], stored[3]) == \
                        (lang[0], blob, html_type[0], template[0]):
                    print("%s unchanged" % path)
                    continue
                db.execute(
                    "INSERT INTO Content (path, languageID, content, contentTypeID, templateId) "
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT(path) DO UPDATE SET "
                    "languageID = excluded.languageID, content = excluded.content, "
                    "contentTypeID = excluded.contentTypeID, templateId = excluded.templateId",
                    (path, lang[0], blob, html_type[0], template[0]))
                changed = True
                print("upserted %s (%d bytes)" % (path, len(blob)))

            row = db.execute("SELECT content FROM Content WHERE path = ?", ("i/" + LINK_FILE,)).fetchone()
            if row is None:
                raise RuntimeError("i/%s is not in the database" % LINK_FILE)
            linked = add_link(brotli(["-d", "-D", dict_path], row[0], work).decode("utf-8"))
            if linked is None:
                print("i/%s already links to %s" % (LINK_FILE, LINK_TARGET))
            else:
                blob = brotli(["-q", "11", "-w", "24", "-D", dict_path], linked.encode("utf-8"), work)
                db.execute("UPDATE Content SET content = ? WHERE path = ?", (blob, "i/" + LINK_FILE))
                changed = True
                print("added a link to i/%s" % LINK_FILE)

            jstopic = "i/jstopics/" + LINK_FILE.replace(".html", ".js")
            row = db.execute("SELECT content FROM Content WHERE path = ?", (jstopic,)).fetchone()
            if row is None:
                print("%s is not stored: the app renders it from the page" % jstopic)
            else:
                linked = add_link_to_jstopic(brotli(["-d", "-D", dict_path], row[0], work).decode("utf-8"))
                if linked is None:
                    print("%s already links to %s" % (jstopic, LINK_TARGET))
                else:
                    blob = brotli(["-q", "11", "-w", "24", "-D", dict_path], linked.encode("utf-8"), work)
                    db.execute("UPDATE Content SET content = ? WHERE path = ?", (blob, jstopic))
                    changed = True
                    print("added a link to %s" % jstopic)

            # Stamp the sets' rows rather than add rows: the app reads wholedb with LIMIT 1 and no order.
            if changed:
                for doc_set in ("content-i", "wholedb"):
                    if db.execute("UPDATE LastChange SET changeTime = CURRENT_TIMESTAMP, who = ? "
                                  "WHERE documentationSet = ?", (WHO, doc_set)).rowcount == 0:
                        db.execute("INSERT INTO LastChange (documentationSet, who) VALUES (?, ?)", (doc_set, WHO))
                print("stamped LastChange: content-i, wholedb")
            else:
                print("nothing changed")
            db.execute("COMMIT")
        except BaseException:
            db.execute("ROLLBACK")
            raise
        db.close()
    finally:
        shutil.rmtree(work, ignore_errors=True)


main()

#!/usr/bin/env python3
"""Builds the ADFA-7083 documentation pages.

Reads pages/*.md and writes one JSON file per page into the output directory,
in the shape documentation.db stores for the Help+Manual pages (templateId
3384, `helpmanual-topic.peb`): file, hmTitle, hmBody and the other facts.

Usage: python3 build.py OUT_DIR

A page is Markdown, a small subset of it, after a front matter block:

    ---
    file: sqlite-js-top.html
    title: Page title
    description: One sentence.
    parent: terminal-top.html
    prev: terminal-top.html
    next: sqlite-js-functions.html
    ---

Supported: `##` and `###` headings, paragraphs, `-` and `1.` lists, `>` notes,
pipe tables, fenced code blocks, `code`, **bold** and [links](page.html). A link to
an http(s) address is shown as text, because the documentation stays on the device.
"""
import html
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Pages of the existing help that these pages hang under, outermost first.
ROOT_TRAIL = [("cogo-top.html", "Code on the Go"), ("terminal-top.html", "Terminal")]

P_STYLE = {
    "p_Normal": "",
    "li": "text-indent: 0; padding-left: 1.2500rem; margin-left: 1.3125rem;",
    "code": "margin: 0.1875rem 0.0625rem 0.1875rem 1.2500rem; white-space: pre-wrap;",
    "note": "margin-left: 1.2500rem; padding-left: 0.5000rem; border-left: 0.1875rem solid #999999;",
    "h2": "page-break-after: avoid;",
    "h3": "page-break-after: avoid;",
}
CELL = "border: 1px solid #999999; padding: 0.2500rem 0.5000rem; text-align: left; vertical-align: top;"


def esc(s):
    return html.escape(s, quote=False)


INLINE = re.compile(r"`([^`]+)`|\*\*((?:[^*`]|`[^`]*`)+)\*\*|\[([^\]]+)\]\(([^)]+)\)")


def inline(s):
    out, pos = [], 0
    for m in INLINE.finditer(s):
        out.append(esc(s[pos:m.start()]))
        if m.group(1) is not None:
            out.append('<span class="f_CodeExample">%s</span>' % esc(m.group(1)))
        elif m.group(2) is not None:
            out.append('<strong style="font-weight: bold;">%s</strong>' % inline(m.group(2)))
        else:
            text, url = m.group(3), m.group(4)
            if re.match(r"https?://", url):
                # CoGo keeps documentation on the device: show the address, do not link it
                out.append("%s (%s)" % (esc(text), esc(url)))
            else:
                out.append('<a href="%s" class="topiclink">%s</a>' % (html.escape(url), esc(text)))
        pos = m.end()
    out.append(esc(s[pos:]))
    return "".join(out)


def paragraph(text):
    return '<p class="p_Normal">%s</p>' % inline(text)


def list_item(marker, text):
    return ('<p class="p_List_Level1" style="%s"><span class="f_List_Level1" '
            'style="display:inline-block;width:1.2500rem;margin-left:-1.2500rem">%s</span>%s</p>'
            % (P_STYLE["li"], marker, inline(text)))


def code_block(lines):
    text = "\n".join(lines)
    return ('<p class="p_CodeExample" style="%s"><span class="f_CodeExample">%s</span></p>'
            % (P_STYLE["code"], esc(text)))


def table(rows):
    def cells(row):
        return [c.strip() for c in row.strip().strip("|").split("|")]
    head, body = cells(rows[0]), [cells(r) for r in rows[2:]]
    out = ['<table style="border-collapse: collapse; margin: 0.3125rem 0 0.5000rem 0;"><tr>']
    out += ['<th style="%s">%s</th>' % (CELL, inline(c)) for c in head]
    out.append("</tr>")
    for row in body:
        out.append("<tr>" + "".join('<td style="%s">%s</td>' % (CELL, inline(c)) for c in row) + "</tr>")
    out.append("</table>")
    return "".join(out)


def body_html(md):
    blocks, lines, i = [], md.split("\n"), 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
        elif line.startswith("```"):
            j = i + 1
            while not lines[j].startswith("```"):
                j += 1
            blocks.append(code_block(lines[i + 1:j]))
            i = j + 1
        elif line.startswith("## ") or line.startswith("### "):
            level = 2 if line.startswith("## ") else 3
            text = line[level + 1:]
            blocks.append('<p class="p_Heading%d" style="%s"><span class="f_Heading%d">%s</span></p>'
                          % (level, P_STYLE["h%d" % level], level, inline(text)))
            i += 1
        elif line.startswith("- ") or re.match(r"\d+\. ", line):
            while i < len(lines) and (lines[i].startswith("- ") or re.match(r"\d+\. ", lines[i])):
                m = re.match(r"(\d+\.) (.*)", lines[i])
                blocks.append(list_item(m.group(1), m.group(2)) if m else list_item("&#8226;", lines[i][2:]))
                i += 1
        elif line.startswith("|"):
            j = i
            while j < len(lines) and lines[j].startswith("|"):
                j += 1
            blocks.append(table(lines[i:j]))
            i = j
        elif line.startswith("> "):
            j = i
            while j < len(lines) and lines[j].startswith("> "):
                j += 1
            text = " ".join(l[2:] for l in lines[i:j])
            blocks.append('<p class="p_Normal" style="%s">%s</p>' % (P_STYLE["note"], inline(text)))
            i = j
        else:
            j = i
            while j < len(lines) and lines[j].strip() and not re.match(r"```|## |### |- |\d+\. |\||> ", lines[j]):
                j += 1
            blocks.append(paragraph(" ".join(l.strip() for l in lines[i:j])))
            i = j
    return "\r\n".join(blocks) + "\r\n"


def read_page(path):
    text = open(path, encoding="utf-8").read()
    m = re.match(r"---\n(.*?)\n---\n(.*)", text, re.S)
    meta = dict(l.split(": ", 1) for l in m.group(1).split("\n"))
    return meta, m.group(2)


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    pages = {}
    for name in sorted(os.listdir(os.path.join(HERE, "pages"))):
        if name.endswith(".md"):
            meta, md = read_page(os.path.join(HERE, "pages", name))
            pages[meta["file"]] = (meta, md)
    titles = dict(ROOT_TRAIL)
    titles.update({f: m["title"] for f, (m, _) in pages.items()})
    parents = {f: m["parent"] for f, (m, _) in pages.items()}

    for file, (meta, md) in pages.items():
        trail, p = [], meta["parent"]
        while p in titles and p != "cogo-top.html":
            trail.insert(0, p)
            p = parents.get(p, "terminal-top.html" if p != "terminal-top.html" else "cogo-top.html")
        trail.insert(0, "cogo-top.html")
        crumbs = " &gt; ".join('<a href="%s">%s</a>' % (f, esc(titles[f])) for f in trail)
        facts = {
            "file": file,
            "hmKeywords": meta.get("keywords", ""),
            "hmTitle": meta["title"],
            "hmDescription": meta["description"],
            "hmPrevLink": meta.get("prev", ""),
            "hmNextLink": meta.get("next", ""),
            "hmParentLink": meta["parent"],
            "hmBreadCrumbs": crumbs,
            "hmTitlePath": " > ".join([titles[f] for f in trail] + [meta["title"]]),
            "hmHeader": '<h1 class="p_Heading1" style="page-break-after: avoid;"><span class="f_Heading1">%s</span></h1>\n\r'
                        % esc(meta["title"]),
            "hmBody": body_html(md),
        }
        with open(os.path.join(out_dir, file + ".json"), "w", encoding="utf-8") as f:
            json.dump(facts, f, ensure_ascii=False)
        print("built", file, len(facts["hmBody"]), "bytes of body")


main()

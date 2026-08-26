-- ADFA-5212: Dynamic Bookshelf tables, data, and template.
--
-- Creates BookCategories and Bookshelf if they are missing, fills them with
-- the books currently shipped by the bookshelf plugin, and installs the
-- 'bookshelf' Pebble template that WebServer renders at /pr/bs.
--
-- Supersedes the three prototype scripts attached to ADFA-5212. Those had
-- `CREATE TABLE <name> IF NOT EXISTS` (SQLite wants that clause before the
-- name, so each was a parse error), no IF NOT EXISTS at all on Templates, and
-- hard-coded Content.id values. One file rather than three because the
-- sections depend on each other and share the safety harness below.
--
-- Apply against the real documentation.db:
--   sqlite3 documentation.db < ADFA-5212-dynamic-bookshelf.sql
--
-- Two properties the prototypes did not have:
--
--   * Idempotent. Running it twice leaves the same rows: categories are
--     updated in place (ids preserved, because Bookshelf references them),
--     the book list is rebuilt, and the template is updated if already there.
--
--   * No hard-coded Content.id. Those are AUTOINCREMENT values assigned at
--     import time and differ in every rebuild of the database, which is what
--     the prototype's own comment warned about. Books resolve by Content.path
--     instead, which is stable, and a book whose path is missing from Content
--     is simply not inserted rather than pointing at whichever row happens to
--     hold that id.
--
-- `.bail on` matters more than it looks: without it the sqlite3 CLI reports an
-- error, carries on, and reaches COMMIT anyway, persisting whatever succeeded
-- (see docs/documentation-database.md). Running the prototypes against a copy
-- of the 14-Aug database did exactly that, leaving 22 Bookshelf rows: the 7
-- good books on top of the 15 broken ones.

.bail on

-- Enforced so an inserted bookCategoryID that does not resolve is an error
-- here rather than an empty bookshelf later. Must be set outside the
-- transaction; SQLite ignores it inside one.
PRAGMA foreign_keys = ON;

BEGIN TRANSACTION;

-- ---------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------
-- Definitions match the tables already present in shipped databases, so an
-- existing database keeps its schema and only the data below changes.

CREATE TABLE IF NOT EXISTS BookCategories (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  category    TEXT,
  description TEXT DEFAULT '',
  UNIQUE(category)
);

CREATE TABLE IF NOT EXISTS Bookshelf (
  contentID      INTEGER NOT NULL,
  title          TEXT DEFAULT '',
  description    TEXT DEFAULT '',
  bookCategoryID INTEGER,
  FOREIGN KEY (bookCategoryID) REFERENCES BookCategories(id),
  UNIQUE(title, bookCategoryID)
);

-- ---------------------------------------------------------------------
-- Seed data
-- ---------------------------------------------------------------------
-- In temp tables so the sections below can join on them and the verification
-- at the end can compare against what was asked for.
--
-- Descriptions are inserted into HTML by the template, so the entities are
-- deliberate. Stored as the characters themselves, not HTML entities: Pebble
-- here, matching the others.

CREATE TEMP TABLE CategorySeed (
  category    TEXT NOT NULL,
  description TEXT NOT NULL
);

INSERT INTO CategorySeed (category, description) VALUES
  ('General',   'Books about computing'),
  ('Java',      'Books about the Java programming language'),
  ('Kotlin',    'Books about the Kotlin programming language'),
  ('Pebble',    'Books about the Pebble template mini-language'),
  ('Android',   'Books about Android mobile programming'),
  ('C and C++', 'Books about the C and C++ programming languages and the C Preprocessor');

CREATE TEMP TABLE BookSeed (
  path        TEXT NOT NULL,
  title       TEXT NOT NULL,
  description TEXT NOT NULL,
  category    TEXT NOT NULL
);

INSERT INTO BookSeed (path, title, description, category) VALUES
  ('bookshelf/org.appdevforall.bookshelfplugin/AndroidNotesForProfessionals.pdf',
   'Android Notes for Professionals',
   'Compiled from Stack Overflow. 266 chapters, 1297 pages covers Activities, Fragments, RecyclerViews, JSON parsing, Background Tasks, and more',
   'Android'),
  ('bookshelf/org.appdevforall.bookshelfplugin/BeejGuideToCProgramming.pdf',
   'Beej Guide To C Programming',
   'By Brian “Beej Jorgensen” Hall. 41 chapters, 342 pages covers C, the preprocessor, and the standard library',
   'C and C++'),
  ('bookshelf/org.appdevforall.bookshelfplugin/JavaJavaJava.pdf',
   'Java, Java, Java: Object-Oriented Problem Solving',
   'By Ralph Morelli and Ralph Walde. 16 chapters and 8 appendices, 840 pages covers basic Java including exceptions, also graphics, threads, and socket programming',
   'Java'),
  ('bookshelf/org.appdevforall.bookshelfplugin/JavaNotesForProfessionals.pdf',
   'Java Notes for Professionals',
   'Compiled from Stack Overflow. 181 chapters and 4 appendices, 951 pages covering Syntax, Semantics, Compilation, Documentation, Libraries, Generics, and more',
   'Java'),
  ('bookshelf/org.appdevforall.bookshelfplugin/KotlinNotesForProfessionals.pdf',
   'Kotlin Notes for Professionals',
   'Compiled from Stack Overflow. 94 pages, covering Basics & Control Flow, Null Safety, Object-Oriented & Functional Mix, Advanced Features, Java Interoperability, Android Specifics, and more',
   'Kotlin'),
  ('bookshelf/org.appdevforall.bookshelfplugin/ModernCplusplusTutorialOuChangkun.pdf',
   'Modern C++ Tutorial',
   'By Ou Changkun. 10 chapters and 2 appendices, 111 pages, “C++ programmers who are still using traditional C++ (this book refers to C++98 and its previous standards as traditional C++) may even amazed by the fact that they are not using the same language while reading modern C++ code.”',
   'C and C++'),
  ('bookshelf/org.appdevforall.bookshelfplugin/PebbleTemplateGuide.pdf',
   'Pebble Template Guide',
   '89 pages, a PDF version of the website',
   'Pebble');

-- ---------------------------------------------------------------------
-- BookCategories
-- ---------------------------------------------------------------------
-- Insert the missing ones, then refresh every description. Ids are never
-- reassigned: Bookshelf.bookCategoryID points at them, and INSERT OR REPLACE
-- would delete and re-add the row with a new id.

INSERT INTO BookCategories (category, description)
SELECT S.category, S.description
  FROM CategorySeed S
 WHERE NOT EXISTS (SELECT 1 FROM BookCategories BC WHERE BC.category = S.category);

UPDATE BookCategories
   SET description = (SELECT S.description FROM CategorySeed S WHERE S.category = BookCategories.category)
 WHERE category IN (SELECT category FROM CategorySeed);

-- ---------------------------------------------------------------------
-- Bookshelf
-- ---------------------------------------------------------------------
-- Rebuilt rather than merged. The 14-Aug database's rows are unusable (NULL
-- bookCategoryID, datetime-placeholder titles; see ADFA-5204), and adding to
-- them leaves real books beside placeholder ones.
--
-- Scoped to what this migration owns, not "everything". A plugin can contribute
-- non-PDF books to this catalog (see docs/documentation-database.md), and those
-- rows are none of this script's business: DELETE FROM Bookshelf would have taken
-- them out and the checks below would still have passed, because they counted only
-- the seeded rows they expected to find.
--
-- Only rows this migration is about to re-seed. There was a second clause,
-- bookCategoryID IS NULL, meant to sweep up the ADFA-5204 placeholder rows -- but
-- NULL does not mean what it assumed. The AddBook trigger is
--
--   AFTER INSERT ON Content WHEN NEW.path LIKE '%.pdf'
--     INSERT INTO Bookshelf (contentID, title) VALUES (NEW.id, CURRENT_TIMESTAMP || NEW.id);
--
-- so it writes contentID and a timestamp title and nothing else. A NULL category and
-- a datetime-looking title are what *every* freshly ingested PDF has until someone
-- curates it -- the placeholder rows and the pending ones are the same rows, and no
-- WHERE clause can separate them. DeleteBook only fires on a Content DELETE, so
-- anything removed here never comes back.

DELETE FROM Bookshelf
 WHERE contentID IN (SELECT C.id FROM Content C JOIN BookSeed S ON C.path = S.path);

INSERT INTO Bookshelf (contentID, title, description, bookCategoryID)
SELECT C.id, S.title, S.description, BC.id
  FROM BookSeed S
  JOIN Content C ON C.path = S.path
  JOIN BookCategories BC ON BC.category = S.category;

-- ---------------------------------------------------------------------
-- Templates: the 'bookshelf' Pebble template
-- ---------------------------------------------------------------------
-- WebServer looks this row up by name, so its id does not matter -- but the id
-- is left alone on an update, because Content.templateId refers to template
-- ids numerically and reassigning them is a hazard worth avoiding entirely.
--
-- The blob is the template verified on device on 19-Aug, with the debug output
-- removed (it was most of the rendered page), reworked to loop over the payload
-- instead of naming categories. It used to call a filter-by-category macro once
-- per category, and named five while the seed above defines six, so General --
-- which is also WebServer's IFNULL fallback name for an uncategorised book --
-- could never appear on the page. Display order now comes from the query's
-- ORDER BY BC.category rather than the call order; pinning a different order
-- needs a sort column in BookCategories, not a template edit.
--
-- Held in a temp table so the INSERT and the UPDATE cannot drift apart: they
-- used to carry two copies of the literal, and the check at the bottom compared
-- only its length.

CREATE TEMP TABLE TemplateBlob AS SELECT X'3C21444F43545950452068746D6C3E0A3C68746D6C206C616E673D22656E2D7573223E0A3C686561643E0A3C7374796C6520747970653D22746578742F637373223E0A2E66696C656C696E6B207B206261636B67726F756E642D636F6C6F723A2079656C6C6F773B207D0A2E7765626C696E6B207B20206261636B67726F756E642D636F6C6F723A206379616E3B7D0A3C2F7374796C653E0A3C2F686561643E3C626F64793E3C703E54686520666F6C6C6F77696E6720626F6F6B7320616E64207265666572656E6365206D6174657269616C732061726520696E636C75646564207769746820436F6465206F6E2074686520476F2E3C2F703E0A7B2520666F72206974656D20696E20726573756C7420257D0A3C68313E43617465676F72793A207B7B206974656D2E63617465676F7279207D7D3C2F68313E0A7B25206966206974656D2E6465736372697074696F6E20257D0A3C68323E4465736372697074696F6E3A207B7B206974656D2E6465736372697074696F6E207D7D3C2F68323E0A7B2520656E64696620257D0A20207B2520666F7220626F6F6B20696E206974656D2E626F6F6B7320257D0A3C703E0A202020207B2520696620626F6F6B2E706466203D3D203120257D0A20203C6120687265663D222F702F7765622F7669657765722E68746D6C3F66696C653D2F7B7B20626F6F6B2E6C696E6B207D7D22207461726765743D225F626C616E6B2220636C6173733D2266696C656C696E6B223E7B7B20626F6F6B2E7469746C65207D7D3C2F613E0A2020202020207B2520696620626F6F6B2E6465736372697074696F6E20257D0A2020202020202020287B7B20626F6F6B2E6465736372697074696F6E207D7D203C693E5044463C2F693E290A2020202020207B2520656E64696620257D0A202020207B2520656C736520257D0A20203C6120687265663D222F7B7B20626F6F6B2E6C696E6B207D7D22207461726765743D225F626C616E6B2220636C6173733D227765626C696E6B223E7B7B20626F6F6B2E7469746C65207D7D3C2F613E0A2020202020207B2520696620626F6F6B2E6465736372697074696F6E20257D0A2020202020202020287B7B20626F6F6B2E6465736372697074696F6E207D7D290A2020202020207B2520656E64696620257D0A202020207B2520656E64696620257D0A3C2F703E0A20207B2520656E64666F7220257D0A7B2520656E64666F7220257D0A3C2F626F64793E3C2F68746D6C3E0A' AS content;

INSERT INTO Templates (name, content)
SELECT 'bookshelf', (SELECT content FROM TemplateBlob)
 WHERE NOT EXISTS (SELECT 1 FROM Templates WHERE name = 'bookshelf');

UPDATE Templates
   SET content = (SELECT content FROM TemplateBlob)
 WHERE name = 'bookshelf';

-- ---------------------------------------------------------------------
-- Verification
-- ---------------------------------------------------------------------
-- Each statement records a row only when its invariant is violated, so a clean
-- run collects nothing. The SELECT then prints whatever was collected -- naming
-- the problem, which a bare CHECK failure would not -- and the gate turns a
-- non-empty list into an error `.bail on` acts on, rolling the transaction back.
--
-- Written this way because SQLite prohibits subqueries inside CHECK, so the
-- conditions have to live in INSERT ... WHERE.

CREATE TEMP TABLE Problems (
  problem TEXT NOT NULL
);

-- Every seeded book resolved to a Content row. This is the check that catches a
-- database whose Content.path values differ from the seed list.
INSERT INTO Problems (problem)
SELECT 'these seeded book paths are missing from Content: ' || GROUP_CONCAT(path, '; ')
  FROM BookSeed S
 WHERE NOT EXISTS (SELECT 1 FROM Content C WHERE C.path = S.path)
-- Without this the aggregate still returns one row on a clean run, GROUP_CONCAT
-- is NULL, and the insert fails where nothing is wrong.
HAVING COUNT(*) > 0;

-- Every seeded book is on the shelf. Counted over the seeded rows rather than the
-- whole table, so a plugin's own books neither satisfy this check nor break it.
INSERT INTO Problems (problem)
SELECT 'Bookshelf holds ' || (SELECT COUNT(*) FROM Bookshelf B, Content C, BookSeed S
                               WHERE C.id = B.contentID AND C.path = S.path)
       || ' of the ' || (SELECT COUNT(*) FROM BookSeed) || ' seeded books'
 WHERE (SELECT COUNT(*) FROM Bookshelf B, Content C, BookSeed S
         WHERE C.id = B.contentID AND C.path = S.path) <> (SELECT COUNT(*) FROM BookSeed);

-- ...and every seeded one joins through to a category, which is what the ADFA-5204
-- breakage failed: rows present, join empty.
INSERT INTO Problems (problem)
SELECT 'only ' || (SELECT COUNT(*) FROM Content C, Bookshelf B, BookCategories BC, BookSeed S
                    WHERE C.id = B.contentID AND B.bookCategoryID = BC.id AND C.path = S.path)
       || ' of ' || (SELECT COUNT(*) FROM BookSeed) || ' seeded books join to a category'
 WHERE (SELECT COUNT(*) FROM Content C, Bookshelf B, BookCategories BC, BookSeed S
         WHERE C.id = B.contentID AND B.bookCategoryID = BC.id AND C.path = S.path)
       <> (SELECT COUNT(*) FROM BookSeed);

-- Nothing else is on the shelf that this script did not put there or knowingly
-- leave alone. The ADFA-5204 symptom was a shelf holding rows nobody expected, and
-- a check scoped to the seeded rows cannot see those at all. Uncurated books are
-- reported, not deleted: they are pending curation, not junk (see the DELETE above).
INSERT INTO Problems (problem)
SELECT 'note: Bookshelf holds ' || (SELECT COUNT(*) FROM Bookshelf) || ' rows, of which '
       || (SELECT COUNT(*) FROM BookSeed) || ' are seeded here and '
       || (SELECT COUNT(*) FROM Bookshelf WHERE bookCategoryID IS NULL)
       || ' are uncurated (no category, so they render under General)'
 WHERE (SELECT COUNT(*) FROM Bookshelf) <> (SELECT COUNT(*) FROM BookSeed);

-- Every seeded category exists.
INSERT INTO Problems (problem)
SELECT 'these seeded categories are missing: ' || GROUP_CONCAT(category, '; ')
  FROM CategorySeed S
 WHERE NOT EXISTS (SELECT 1 FROM BookCategories BC WHERE BC.category = S.category)
HAVING COUNT(*) > 0;

-- Exactly one template row, holding exactly this template. Compared by content:
-- a length check passes a wrong template of the right size, which is how the
-- double-escaped descriptions shipped in the first place.
INSERT INTO Problems (problem)
SELECT 'expected one bookshelf template matching this script, found '
       || (SELECT COUNT(*) FROM Templates WHERE name = 'bookshelf') || ' row(s), '
       || IFNULL((SELECT LENGTH(content) FROM Templates WHERE name = 'bookshelf'), 0)
       || ' bytes against the expected '
       || (SELECT LENGTH(content) FROM TemplateBlob) || ' bytes'
 WHERE (SELECT COUNT(*) FROM Templates WHERE name = 'bookshelf') <> 1
    OR (SELECT content FROM Templates WHERE name = 'bookshelf')
       IS NOT (SELECT content FROM TemplateBlob);

-- Prints one line per problem; silent on a clean run.
SELECT 'VERIFICATION FAILED: ' || problem FROM Problems;

-- Any problem makes this insert violate the CHECK, which aborts and rolls back.
CREATE TEMP TABLE Gate (
  ok INTEGER NOT NULL CHECK (ok = 1)
);
INSERT INTO Gate (ok)
SELECT CASE WHEN (SELECT COUNT(*) FROM Problems) = 0 THEN 1 ELSE 0 END;

DROP TABLE Gate;
DROP TABLE Problems;
DROP TABLE BookSeed;
DROP TABLE CategorySeed;

COMMIT;

-- What the result should look like:
--
--   Android    |Android Notes for Professionals
--   C and C++  |Beej Guide To C Programming
--   C and C++  |Modern C++ Tutorial
--   Java       |Java Notes for Professionals
--   Java       |Java, Java, Java: Object-Oriented Problem Solving
--   Kotlin     |Kotlin Notes for Professionals
--   Pebble     |Pebble Template Guide

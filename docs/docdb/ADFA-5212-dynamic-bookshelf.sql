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
-- deliberate. The prototype had a bare '&' in the Kotlin entry; it is '&amp;'
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
   'By Brian &ldquo;Beej Jorgensen&rdquo; Hall. 41 chapters, 342 pages covers C, the preprocessor, and the standard library',
   'C and C++'),
  ('bookshelf/org.appdevforall.bookshelfplugin/JavaJavaJava.pdf',
   'Java, Java, Java: Object-Oriented Problem Solving',
   'By Ralph Morelli and Ralph Wade. 16 chapters and 8 appendices, 840 pages covers basic Java including exceptions, also graphics, threads, and socket programming',
   'Java'),
  ('bookshelf/org.appdevforall.bookshelfplugin/JavaNotesForProfessionals.pdf',
   'Java Notes for Professionals',
   'Compiled from Stack Overflow. 181 chapters and 4 appendices, 951 pages covering Syntax, Semantics, Compilation, Documentation, Libraries, Generics, and more',
   'Java'),
  ('bookshelf/org.appdevforall.bookshelfplugin/KotlinNotesForProfessionals.pdf',
   'Kotlin Notes for Professionals',
   'Compiled from Stack Overflow. 94 pages, covering Basics &amp; Control Flow, Null Safety, Object-Oriented &amp; Functional Mix, Advanced Features, Java Interoperability, Android Specifics, and more',
   'Kotlin'),
  ('bookshelf/org.appdevforall.bookshelfplugin/ModernCplusplusTutorialOuChangkun.pdf',
   'Modern C++ Tutorial',
   'By Ou Changkun. 10 chapters and 2 appendices, 111 pages, &ldquo;C++ programmers who are still using traditional C++ (this book refers to C++98 and its previous standards as traditional C++) may even amazed by the fact that they are not using the same language while reading modern C++ code.&rdquo;',
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
-- them leaves real books beside placeholder ones. If a database ever carries
-- bookshelf rows from another source, narrow this DELETE to the seeded paths.

DELETE FROM Bookshelf;

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
-- The blob is the template verified on device on 19-Aug: 1261 bytes, with the
-- debug output removed (it was most of the rendered page) and its five
-- category names matching the seed data above. Those names are hardcoded in
-- the template on purpose, to control display order without a sort column in
-- BookCategories, so a seventh category needs a template edit too.

INSERT INTO Templates (name, content)
SELECT 'bookshelf', X'3c21444f43545950452068746d6c3e0a3c68746d6c206c616e673d22656e2d7573223e0a3c686561643e0a3c7374796c6520747970653d22746578742f637373223e0a2e66696c656c696e6b207b206261636b67726f756e642d636f6c6f723a2079656c6c6f773b207d0a2e7765626c696e6b207b20206261636b67726f756e642d636f6c6f723a206379616e3b7d0a3c2f7374796c653e0a7b25206d6163726f20657870616e64426f6f6b7328726573756c742c2063617465676f72792920257d0a20207b2520666f72206974656d20696e20726573756c7420257d0a202020207b25206966206974656d2e63617465676f7279203d3d2063617465676f727920257d0a3c68313e43617465676f72793a207b7b2063617465676f7279207d7d3c2f68313e0a7b25206966206974656d2e6465736372697074696f6e20213d20272720257d0a3c68323e4465736372697074696f6e3a207b7b206974656d2e6465736372697074696f6e207d7d3c2f68323e0a7b2520656e64696620257d0a2020202020207b2520666f7220626f6f6b20696e206974656d2e626f6f6b7320257d0a3c703e0a20202020202020207b2520696620626f6f6b2e706466203d3d203120257d0a20203c6120687265663d222f702f7765622f7669657765722e68746d6c3f66696c653d2f7b7b20626f6f6b2e6c696e6b207d7d22207461726765743d225f626c616e6b2220636c6173733d2266696c656c696e6b223e7b7b20626f6f6b2e7469746c65207d7d3c2f613e0a202020202020202020207b2520696620626f6f6b2e6465736372697074696f6e20213d20272720257d0a202020202020202020202020287b7b20626f6f6b2e6465736372697074696f6e207d7d203c693e5044463c2f693e290a202020202020202020207b2520656e64696620257d0a20202020202020207b2520656c736520257d0a20203c6120687265663d222f7b7b20626f6f6b2e6c696e6b207d7d22207461726765743d225f626c616e6b2220636c6173733d227765626c696e6b223e7b7b20626f6f6b2e7469746c65207d7d3c2f613e0a202020202020202020207b2520696620626f6f6b2e6465736372697074696f6e20213d20272720257d0a202020202020202020202020287b7b20626f6f6b2e6465736372697074696f6e207d7d290a202020202020202020207b2520656e64696620257d0a20202020202020207b2520656e64696620257d0a3c2f703e0a2020202020207b2520656e64666f7220257d0a202020207b2520656e64696620257d0a20207b2520656e64666f7220257d0a7b2520656e646d6163726f20257d0a0a3c2f686561643e3c626f64793e3c703e54686520666f6c6c6f77696e6720626f6f6b7320616e64207265666572656e6365206d6174657269616c732061726520696e636c75646564207769746820436f6465206f6e2074686520476f2e3c2f703e0a7b7b20657870616e64426f6f6b7328726573756c742c2027416e64726f69642729207d7d0a7b7b20657870616e64426f6f6b7328726573756c742c20274a6176612729207d7d0a7b7b20657870616e64426f6f6b7328726573756c742c20274b6f746c696e2729207d7d0a7b7b20657870616e64426f6f6b7328726573756c742c20274320616e6420432b2b2729207d7d0a7b7b20657870616e64426f6f6b7328726573756c742c2027506562626c652729207d7d0a3c2f626f64793e3c2f68746d6c3e0a'
 WHERE NOT EXISTS (SELECT 1 FROM Templates WHERE name = 'bookshelf');

UPDATE Templates
   SET content = X'3c21444f43545950452068746d6c3e0a3c68746d6c206c616e673d22656e2d7573223e0a3c686561643e0a3c7374796c6520747970653d22746578742f637373223e0a2e66696c656c696e6b207b206261636b67726f756e642d636f6c6f723a2079656c6c6f773b207d0a2e7765626c696e6b207b20206261636b67726f756e642d636f6c6f723a206379616e3b7d0a3c2f7374796c653e0a7b25206d6163726f20657870616e64426f6f6b7328726573756c742c2063617465676f72792920257d0a20207b2520666f72206974656d20696e20726573756c7420257d0a202020207b25206966206974656d2e63617465676f7279203d3d2063617465676f727920257d0a3c68313e43617465676f72793a207b7b2063617465676f7279207d7d3c2f68313e0a7b25206966206974656d2e6465736372697074696f6e20213d20272720257d0a3c68323e4465736372697074696f6e3a207b7b206974656d2e6465736372697074696f6e207d7d3c2f68323e0a7b2520656e64696620257d0a2020202020207b2520666f7220626f6f6b20696e206974656d2e626f6f6b7320257d0a3c703e0a20202020202020207b2520696620626f6f6b2e706466203d3d203120257d0a20203c6120687265663d222f702f7765622f7669657765722e68746d6c3f66696c653d2f7b7b20626f6f6b2e6c696e6b207d7d22207461726765743d225f626c616e6b2220636c6173733d2266696c656c696e6b223e7b7b20626f6f6b2e7469746c65207d7d3c2f613e0a202020202020202020207b2520696620626f6f6b2e6465736372697074696f6e20213d20272720257d0a202020202020202020202020287b7b20626f6f6b2e6465736372697074696f6e207d7d203c693e5044463c2f693e290a202020202020202020207b2520656e64696620257d0a20202020202020207b2520656c736520257d0a20203c6120687265663d222f7b7b20626f6f6b2e6c696e6b207d7d22207461726765743d225f626c616e6b2220636c6173733d227765626c696e6b223e7b7b20626f6f6b2e7469746c65207d7d3c2f613e0a202020202020202020207b2520696620626f6f6b2e6465736372697074696f6e20213d20272720257d0a202020202020202020202020287b7b20626f6f6b2e6465736372697074696f6e207d7d290a202020202020202020207b2520656e64696620257d0a20202020202020207b2520656e64696620257d0a3c2f703e0a2020202020207b2520656e64666f7220257d0a202020207b2520656e64696620257d0a20207b2520656e64666f7220257d0a7b2520656e646d6163726f20257d0a0a3c2f686561643e3c626f64793e3c703e54686520666f6c6c6f77696e6720626f6f6b7320616e64207265666572656e6365206d6174657269616c732061726520696e636c75646564207769746820436f6465206f6e2074686520476f2e3c2f703e0a7b7b20657870616e64426f6f6b7328726573756c742c2027416e64726f69642729207d7d0a7b7b20657870616e64426f6f6b7328726573756c742c20274a6176612729207d7d0a7b7b20657870616e64426f6f6b7328726573756c742c20274b6f746c696e2729207d7d0a7b7b20657870616e64426f6f6b7328726573756c742c20274320616e6420432b2b2729207d7d0a7b7b20657870616e64426f6f6b7328726573756c742c2027506562626c652729207d7d0a3c2f626f64793e3c2f68746d6c3e0a'
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

-- The bookshelf holds exactly the seeded books.
INSERT INTO Problems (problem)
SELECT 'Bookshelf has ' || (SELECT COUNT(*) FROM Bookshelf) || ' rows, expected ' || (SELECT COUNT(*) FROM BookSeed)
 WHERE (SELECT COUNT(*) FROM Bookshelf) <> (SELECT COUNT(*) FROM BookSeed);

-- ...and every one joins through to a category, which is what the ADFA-5204
-- breakage failed: rows present, join empty.
INSERT INTO Problems (problem)
SELECT 'only ' || (SELECT COUNT(*) FROM Content C, Bookshelf B, BookCategories BC
                    WHERE C.id = B.contentID AND B.bookCategoryID = BC.id)
       || ' of ' || (SELECT COUNT(*) FROM BookSeed) || ' books join to a category'
 WHERE (SELECT COUNT(*) FROM Content C, Bookshelf B, BookCategories BC
         WHERE C.id = B.contentID AND B.bookCategoryID = BC.id) <> (SELECT COUNT(*) FROM BookSeed);

-- Every seeded category exists.
INSERT INTO Problems (problem)
SELECT 'these seeded categories are missing: ' || GROUP_CONCAT(category, '; ')
  FROM CategorySeed S
 WHERE NOT EXISTS (SELECT 1 FROM BookCategories BC WHERE BC.category = S.category)
HAVING COUNT(*) > 0;

-- Exactly one template row, of the expected size.
INSERT INTO Problems (problem)
SELECT 'expected one bookshelf template of 1261 bytes, found '
       || (SELECT COUNT(*) FROM Templates WHERE name = 'bookshelf') || ' row(s) of '
       || IFNULL((SELECT LENGTH(content) FROM Templates WHERE name = 'bookshelf'), 0) || ' bytes'
 WHERE (SELECT COUNT(*) FROM Templates WHERE name = 'bookshelf') <> 1
    OR (SELECT LENGTH(content) FROM Templates WHERE name = 'bookshelf') <> 1261;

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

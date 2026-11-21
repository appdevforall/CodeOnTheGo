#!/usr/bin/env python3
"""Checks the examples of the app pages (pages/sqlite-js-app-*.md).

- Every ```gradle example must be lines of the template's app/build.gradle.kts, in order.
- Every ```kotlin example is compiled, as written: the examples go, in page order, into one
  file of a project made from the JavaScript in SQLite template, which is then built with
  the sqlite-js AAR under test. app-test/DocExamplesTest.kt, an instrumented test that
  calls the examples, is compiled with them. With DEVICE=1 it also runs, on the device adb
  reaches (ANDROID_SERIAL picks one): it installs and removes its own app there.
- The SQL in the Kotlin examples, the statements that create the JavaScript functions and the
  DAO queries, runs on this host in sqlite3 with sqlite-js loaded.

Usage:
  DEV_ASSETS=/path/to/dev-assets AAR=/path/to/sqlite-js.aar ANDROID_HOME=/path/to/sdk \\
  SQLITE3=sqlite3 EXT=/path/to/sqlite-js.so [GRADLE=gradle] [DEVICE=1] \\
  python3 test_app_examples.py WORK_DIR

DEV_ASSETS is a checkout of appdevforall/dev-assets: its templates/JavaScriptInSQLite and
scripts/render-template.py are used. WORK_DIR is made, or emptied. The build downloads what
Gradle has not cached. Prints TAP. Exits 0 only if every check passes.
"""
import glob
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SQLITE3 = os.environ.get("SQLITE3", "sqlite3")
EXT = os.environ.get("EXT", "sqlite-js")
GRADLE = os.environ.get("GRADLE", "gradle")
DEVICE = os.environ.get("DEVICE") == "1"
PACKAGE = "docexamples"
# What the examples use. An example shows no imports: the pages name the packages.
IMPORTS = """
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.SkipQueryVerification
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.sqlitejs.SqliteJs
"""
# Added to the project's app/build.gradle.kts for the instrumented test. Its own application id, so that the
# test never replaces an app made from the template on the device.
DEVICE_BUILD = """
android {
    defaultConfig {
        applicationId = "org.appdevforall.sqlitejs.docexamples"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
"""
# The template's sample products, and what the app pages say the app shows for them.
PRODUCTS = [("Red Apple", 0.5), ("Whole Wheat Bread", 3.25), ("Milk, 1 litre", 1.2), ("Coffee & Tea Sampler", 12.0)]
SLUGS = ["red-apple", "whole-wheat-bread", "milk-1-litre", "coffee-tea-sampler"]
MEDIAN = "2.225"

n = failed = 0


def check(ok, what, detail=""):
    global n, failed
    n += 1
    print("%s %d - %s" % ("ok" if ok else "not ok", n, what))
    if not ok:
        failed += 1
        for line in str(detail).strip().split("\n")[-40:]:
            print("#   " + line)
    return ok


def blocks(kind):
    """(page name, example number, text) of each example of that kind, in page order."""
    found = []
    for path in sorted(glob.glob(os.path.join(HERE, "pages", "sqlite-js-app-*.md"))):
        text = open(path, encoding="utf-8").read()
        for k, block in enumerate(re.findall(r"^```%s\n(.*?)^```" % kind, text, re.M | re.S), 1):
            found.append((os.path.basename(path), k, block))
    return found


def in_order(lines, text):
    """The first of lines that is not a line of text after the ones before it, or None."""
    have, at = [l.strip() for l in text.split("\n")], 0
    for line in (l.strip() for l in lines if l.strip()):
        try:
            at = have.index(line, at) + 1
        except ValueError:
            return line
    return None


def kotlin_strings(kotlin):
    """The SQL statements in the examples' string literals: ("create" or "query", sql)."""
    found = []
    for m in re.finditer(r'"""(.*?)"""|"((?:[^"\\\n]|\\.)*)"', kotlin, re.S):
        raw, plain = m.group(1), m.group(2)
        sql = raw if raw is not None else re.sub(r"\\(.)", r"\1", plain)
        before = kotlin[:m.start()].rstrip()
        if before.endswith("@Query("):
            found.append(("query", sql.strip()))
        elif re.match(r"\s*SELECT js_create_", sql):
            found.append(("create", sql.strip()))
    return found


def sqlite(sql, timeout=60):
    r = subprocess.run([SQLITE3, "-batch", "-noheader", "-cmd", '.load "%s"' % EXT, ":memory:"],
                       input=sql + "\n", capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip().split("\n"), r.stderr.strip()


def gradle(project, *tasks):
    return subprocess.run([GRADLE, "--no-daemon", "-Dorg.gradle.jvmargs=-Xmx2g", "--console=plain", "-q"]
                          + list(tasks), cwd=project, capture_output=True, text=True)


def main():
    if len(sys.argv) != 2 or not os.environ.get("DEV_ASSETS") or not os.environ.get("AAR"):
        sys.exit(__doc__)
    work, assets = os.path.abspath(sys.argv[1]), os.environ["DEV_ASSETS"]
    project = os.path.join(work, "project")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    subprocess.run([sys.executable, os.path.join(assets, "scripts", "render-template.py"),
                    os.path.join(assets, "templates"), "JavaScriptInSQLite", project, "kotlin"],
                   check=True, stdout=subprocess.DEVNULL)
    shutil.copy(os.environ["AAR"], os.path.join(project, "app", "libs", "sqlite-js.aar"))

    build_file = os.path.join(project, "app", "build.gradle.kts")
    build_text = open(build_file, encoding="utf-8").read()
    for page, k, block in blocks("gradle"):
        missing = in_order(block.split("\n"), build_text)
        check(missing is None, "%s gradle example %d is in the template's app/build.gradle.kts" % (page, k),
              "not found, or out of order: %s" % missing)

    kotlin = "\n".join(block for _, _, block in blocks("kotlin"))
    source = os.path.join(project, "app", "src", "main", "java", PACKAGE)
    os.makedirs(source)
    with open(os.path.join(source, "DocExamples.kt"), "w", encoding="utf-8") as f:
        f.write("package %s\n%s\n%s" % (PACKAGE, IMPORTS, kotlin))
    test_dir = os.path.join(project, "app", "src", "androidTest", "java", PACKAGE)
    os.makedirs(test_dir)
    shutil.copy(os.path.join(HERE, "app-test", "DocExamplesTest.kt"), test_dir)
    with open(build_file, "a", encoding="utf-8") as f:
        f.write(DEVICE_BUILD)
    count = len(blocks("kotlin"))
    r = gradle(project, ":app:assembleDebug")
    check(r.returncode == 0, "the %d kotlin examples compile in the template (:app:assembleDebug)" % count,
          r.stdout + r.stderr)
    r = gradle(project, ":app:assembleDebugAndroidTest")
    check(r.returncode == 0, "the test that runs them on a device compiles (:app:assembleDebugAndroidTest)",
          r.stdout + r.stderr)

    strings = kotlin_strings(kotlin)
    creates = [s for kind, s in strings if kind == "create"]
    queries = [s for kind, s in strings if kind == "query"]
    setup = ["SELECT js_init_table();"] + [s + ";" for s in creates] + [
        "CREATE TABLE Product (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, price REAL NOT NULL);"
    ] + ["INSERT INTO Product (name, price) VALUES ('%s', %r);" % p for p in PRODUCTS]
    out, err = sqlite("\n".join(setup + ["SELECT count(*) FROM js_functions;"]))
    check(not err and out == ["0"] * (1 + len(creates)) + [str(len(creates))],
          "the %d statements that create functions run, and store them" % len(creates), "%r %r" % (out, err))
    endless = [q for q in queries if "spin" in q]
    ending = [q for q in queries if "spin" not in q]
    out, err = sqlite("\n".join(setup + ["SELECT 'queries';"] + [q + ";" for q in ending]))
    got = out[out.index("queries") + 1:] if "queries" in out else out
    check(not err and got == SLUGS + [MEDIAN], "the %d DAO queries that end give the slugs and the median"
          % len(ending), "%r %r" % (got, err))
    top = open(os.path.join(HERE, "pages", "sqlite-js-app-top.md"), encoding="utf-8").read()
    check("\n".join(["Slugs:"] + SLUGS + ["", "Median price: %.2f" % float(MEDIAN)]) in top,
          "sqlite-js-app-top.md shows that output")
    for q in endless:
        try:
            out, err = sqlite("\n".join(setup + [q + ";"]), timeout=3)
            check(False, "the DAO query %r never ends" % q, "%r %r" % (out, err))
        except subprocess.TimeoutExpired:
            check(True, "the DAO query %r never ends (stopped after 3 s)" % q)

    if DEVICE:
        r = gradle(project, ":app:connectedDebugAndroidTest")
        check(r.returncode == 0, "the kotlin examples run on the device (app-test/DocExamplesTest.kt)",
              r.stdout + r.stderr)

    print("1..%d" % n)
    print("# FAILED %d of %d" % (failed, n) if failed else "# passed all %d" % n)
    sys.exit(1 if failed else 0)


main()

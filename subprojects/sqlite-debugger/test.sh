#!/usr/bin/env bash
# Host tests of the SQLite debugger's extension (ADFA-7135): src/test/c/sqlitedbg_test.c, with
# SQLite built from the amalgamation as requery builds it (SQLITE_THREADSAFE=2), under
# AddressSanitizer + UBSan and then ThreadSanitizer.
# Usage: test.sh [--update-fixture] [AMALGAMATION_TARBALL]   (without the tarball, downloads it)
#   --update-fixture  rewrites src/test/resources/.../events.bin and events.tsv, which the JVM test
#                     of EventDecoder reads; without it the committed pair is checked against the
#                     current record layout instead.
# Needs gcc, curl, tar and sha256sum.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)

# SQLite 3.49.1, the version of the vendored headers in ../sqlite-js/upstream/libs (the script
# checks they are the same). sqlite.org's own zip (sqlite-amalgamation-3490100.zip) is the usual
# source; this is the same amalgamation as bundled in the sqlean.py sdist, which PyPI serves where
# sqlite.org is not reachable. sqlean appends its own init code after SQLite's last line, which is
# cut off below. SHA256 is of the tarball, SQLITE3_C_SHA256 of the cut sqlite3.c.
VERSION=3.49.1
SHA256=210d89989226b988d7d6391f837387d3b81e8cd608c997e0bd37826e395970e7
URL=https://files.pythonhosted.org/packages/67/eb/ac95fab0bc4658124b4ec8fbc31fc494165ab4544606ae91b9a489907dad/sqlean_py-3.49.1.tar.gz
SQLITE3_C_SHA256=ff80c36ef1bb44eb357c7ff1d15be77540d41c28fb671088215a6cd12785c5d3
SQLITE_SOURCE_ID="2025-02-18 13:38:58 873d4e274b4988d260ba8354a9718324a1c26187a4ab4c1cc0227c03d0f10e70"

UPDATE=0
if [ "${1:-}" = "--update-fixture" ]; then UPDATE=1; shift; fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
TARBALL=${1:-$WORK/sqlite-$VERSION.tar.gz}
[ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "$URL"
echo "$SHA256  $TARBALL" | sha256sum -c --quiet -
mkdir "$WORK/src"
tar -xzf "$TARBALL" -C "$WORK/src" --strip-components=1
AMAL=$WORK/src/sqlite
sed -i '/^\/\*.* End of sqlite3\.c \*/q' "$AMAL/sqlite3.c"
echo "$SQLITE3_C_SHA256  $AMAL/sqlite3.c" | sha256sum -c --quiet -
grep -q "SQLITE_SOURCE_ID      \"$SQLITE_SOURCE_ID\"" "$AMAL/sqlite3.h" || { echo "unexpected SQLite source id"; exit 1; }

LIBS=$HERE/../sqlite-js/upstream/libs
for h in sqlite3.h sqlite3ext.h; do
	cmp -s "$AMAL/$h" "$LIBS/$h" || { echo "$h differs from $LIBS/$h"; exit 1; }
done

build () { "$@" >"$WORK/build.log" 2>&1 || { cat "$WORK/build.log"; exit 1; }; }
CPP=$HERE/src/main/cpp
DEFS=(-DSQLITE_CORE -DSQLITE_THREADSAFE=2 -DSQLITE_DQS=0 -DSQLITE_DEFAULT_MEMSTATUS=0)

run_with () {
	local name=$1 sanitize=$2
	echo "# $name"
	mkdir "$WORK/$name"
	build gcc -O1 -g -w -fsanitize="$sanitize" "${DEFS[@]}" -I"$AMAL" -c "$AMAL/sqlite3.c" -o "$WORK/$name/sqlite3.o"
	build gcc -O1 -g -Wall -Wextra -Werror -fsanitize="$sanitize" -fno-sanitize-recover=all "${DEFS[@]}" \
		-I"$AMAL" -I"$CPP" "$CPP/sqlitedbg.c" "$HERE/src/test/c/sqlitedbg_test.c" "$WORK/$name/sqlite3.o" \
		-o "$WORK/$name/test" -lpthread -ldl -lm
	TSAN_OPTIONS=halt_on_error=1 "$WORK/$name/test" ${3:+"${@:3}"}
}

RES=$HERE/src/test/resources/org/appdevforall/sqlitedebugger/engine
if [ "$UPDATE" = 1 ]; then
	mkdir -p "$RES"
	run_with asan-ubsan address,undefined --write-fixture "$RES/events.bin" "$RES/events.tsv"
else
	run_with asan-ubsan address,undefined
	"$WORK/asan-ubsan/test" --check-fixture "$RES/events.bin" "$RES/events.tsv"
fi
run_with tsan thread

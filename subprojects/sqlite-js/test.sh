#!/usr/bin/env bash
# Tests sqlite-js on this host: upstream's suite (test/main.c, not vendored) on its source with
# patches/ applied, then src/test/c/glue_test.c, the test of the Android entry points, on the
# vendored upstream/. Run vendor.sh --check first: it shows the two are the same.
# Usage: test.sh [TARBALL]   (without TARBALL, downloads it)
# Needs gcc, make, curl and a JDK (JAVA_HOME, or javac on the PATH) for jni.h.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
eval "$(grep -E '^(VERSION|SHA256|URL)=' "$HERE/vendor.sh")"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
TARBALL=${1:-$WORK/sqlite-js-$VERSION.tar.gz}
[ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "$URL"
echo "$SHA256  $TARBALL" | sha256sum -c --quiet -
SRC=$WORK/src
mkdir "$SRC"
tar -xzf "$TARBALL" -C "$SRC" --strip-components=1
for p in "$HERE"/patches/*.patch; do
	patch -d "$SRC" -p1 --quiet --no-backup-if-mismatch <"$p"
done

# upstream's own build prints many warnings in QuickJS and SQLite: shown only if it fails
build () { "$@" >"$WORK/build.log" 2>&1 || { cat "$WORK/build.log"; exit 1; }; }

echo "# upstream test suite with patches/"
build make -C "$SRC" dist/js.so dist/main
( cd "$SRC" && ./dist/main )

echo "# sqlitejs_android.c"
JDK=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
U=$HERE/upstream
INC=(-I"$U/src" -I"$U/libs" -I"$JDK/include" -I"$JDK/include/linux")
DEFS=(-DQJS_BUILD_LIBC -DSQLITE_CORE)
for f in "$U/src/sqlitejs.c" "$U/libs/quickjs.c" "$SRC/libs/sqlite3.c"; do
	build gcc -O1 -g -w "${DEFS[@]}" "${INC[@]}" -c "$f" -o "$WORK/$(basename "$f" .c).o"
done
build gcc -O1 -g -Wall -Wextra -Werror "${DEFS[@]}" "${INC[@]}" \
	"$HERE/src/test/c/glue_test.c" "$HERE/src/main/cpp/sqlitejs_android.c" \
	"$WORK/sqlitejs.o" "$WORK/quickjs.o" "$WORK/sqlite3.o" -o "$WORK/glue_test" -lm -lpthread -ldl
"$WORK/glue_test"

# again with sqlite-js working as on SQLite before 3.41, which patches 0006 and 0007 are for
echo "# sqlitejs_android.c, as on SQLite 3.40"
build gcc -O1 -g -w "${DEFS[@]}" "${INC[@]}" -Dsqlite3_libversion_number=glue_sqlite3_libversion_number \
	-c "$U/src/sqlitejs.c" -o "$WORK/sqlitejs-3.40.o"
build gcc -O1 -g -Wall -Wextra -Werror "${DEFS[@]}" "${INC[@]}" -DGLUE_AS_SQLITE_3_40 \
	"$HERE/src/test/c/glue_test.c" "$HERE/src/main/cpp/sqlitejs_android.c" \
	"$WORK/sqlitejs-3.40.o" "$WORK/quickjs.o" "$WORK/sqlite3.o" -o "$WORK/glue_test-3.40" -lm -lpthread -ldl
"$WORK/glue_test-3.40"

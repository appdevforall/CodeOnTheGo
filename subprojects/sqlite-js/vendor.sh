#!/usr/bin/env bash
# Rebuilds upstream/ from the sqlite-js release VERSION and patches/, or checks that it matches.
# Usage: vendor.sh [--check] [TARBALL]   (without TARBALL, downloads it)
set -euo pipefail
VERSION=1.4.1
SHA256=d45d394fc7cb37070a64fa248994c7fef7c10cff31541eb57608405753fc3078
URL=https://github.com/sqliteai/sqlite-js/archive/refs/tags/$VERSION.tar.gz
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=false
if [ "${1:-}" = --check ]; then CHECK=true; shift; fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
TARBALL=${1:-$WORK/sqlite-js-$VERSION.tar.gz}
[ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "$URL"
echo "$SHA256  $TARBALL" | sha256sum -c --quiet -
# the archive's one top-level directory is named after the tag or commit: drop it
SRC=$WORK/src
mkdir "$SRC"
tar -xzf "$TARBALL" -C "$SRC" --strip-components=1
for p in "$HERE"/patches/*.patch; do
	patch -d "$SRC" -p1 --quiet --no-backup-if-mismatch <"$p"
done

# QuickJS (quickjs-ng, amalgamated into libs/quickjs.c) is MIT-licensed, with the license only in
# the comment that heads each amalgamated file: every copyright line, then the license text once.
{
	# LC_ALL=C: the order must not depend on the machine's locale, or --check fails elsewhere
	grep -o 'Copyright (c) [^*]*' "$SRC/libs/quickjs.c" | sed 's/[[:space:]]*$//' | LC_ALL=C sort -u
	echo
	awk '/Permission is hereby granted/ { on = 1 }
		on { sub(/^ \* ?/, ""); print }
		on && /THE SOFTWARE\.$/ { exit }' "$SRC/libs/quickjs.c"
} >"$SRC/LICENSE.quickjs"

# What the build uses, and where it goes: upstream/ for the sources, the resources for the notices
FILES="LICENSE LICENSE.quickjs src/sqlitejs.c src/sqlitejs.h libs/quickjs.c libs/quickjs.h libs/quickjs-libc.h libs/sqlite3ext.h libs/sqlite3.h"
NOTICES="LICENSE:META-INF/LICENSE-sqlite-js.txt LICENSE.quickjs:META-INF/LICENSE-quickjs.txt"
RES=$HERE/src/main/resources
if $CHECK; then
	for f in $FILES; do
		cmp -s "$SRC/$f" "$HERE/upstream/$f" || { echo "upstream/$f is not sqlite-js $VERSION with patches/ applied" >&2; exit 1; }
	done
	for n in $NOTICES; do
		cmp -s "$SRC/${n%%:*}" "$RES/${n#*:}" || { echo "src/main/resources/${n#*:} is not ${n%%:*}" >&2; exit 1; }
	done
	echo "upstream/ is sqlite-js $VERSION with patches/ applied"
else
	rm -rf "$HERE/upstream"
	for f in $FILES; do install -D -m 644 "$SRC/$f" "$HERE/upstream/$f"; done
	for n in $NOTICES; do install -D -m 644 "$SRC/${n%%:*}" "$RES/${n#*:}"; done
	echo "upstream/ rebuilt from sqlite-js $VERSION and patches/"
fi

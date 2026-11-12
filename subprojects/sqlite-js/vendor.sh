#!/usr/bin/env bash
# Rebuilds upstream/ from sqlite-js at COMMIT and patches/, or checks that it matches.
# Usage: vendor.sh [--check] [TARBALL]   (without TARBALL, downloads it)
set -euo pipefail
# Upstream merged our changes (sqliteai/sqlite-js #11 to #19) after its 1.3.7 release, so this is a
# commit of its main branch, named as the Termux package names it. Go back to a release tag once
# one includes them.
VERSION=1.3.7-p20261112
COMMIT=c8ae85a29a911977212e6cdd36ed84926b6b8c4e
SHA256=4f4ebb7e44b20762c11bdbfc6b3abd3aa423daec2b461d20fc47598716ce5561
URL=https://github.com/sqliteai/sqlite-js/archive/$COMMIT.tar.gz
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=false
if [ "${1:-}" = --check ]; then CHECK=true; shift; fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
TARBALL=${1:-$WORK/sqlite-js-$COMMIT.tar.gz}
[ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "$URL"
echo "$SHA256  $TARBALL" | sha256sum -c --quiet -
tar -xzf "$TARBALL" -C "$WORK"
SRC=$WORK/sqlite-js-$COMMIT
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

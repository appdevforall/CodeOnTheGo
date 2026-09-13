#!/usr/bin/env bash
#
# ADFA-5156 / R8 plugin-impact tooling. See README.md in this directory.
#
# Extracts classes*.dex from an APK or .cgp (both are zips) and produces a
# dexdump text file for analyze-plugin-impact.py to consume.
#
#   ./dex-dump.sh <apk-or-cgp> <outdir>                 # one artifact
#   ./dex-dump.sh --disassemble <cgp>... <outdir>       # many, with bytecode
#
# --disassemble (dexdump -d) is required for plugins, because the analysis needs
# invoke instructions to find call sites. It is NOT needed for the IDE APK,
# where only the class/method table is read -- and it would be very slow there.

set -uo pipefail

DEXDUMP="${DEXDUMP:-}"
if [ -z "$DEXDUMP" ]; then
	# Prefer the newest build-tools install we can find.
	for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
		[ -n "$sdk" ] || continue
		cand=$(ls -d "$sdk"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1)
		[ -n "$cand" ] && { DEXDUMP="$cand"; break; }
	done
fi
if [ ! -x "${DEXDUMP:-}" ]; then
	echo "dexdump not found. Set DEXDUMP=/path/to/build-tools/<ver>/dexdump" >&2
	exit 1
fi

DIS=0
if [ "${1:-}" = "--disassemble" ]; then
	DIS=1
	shift
fi

if [ "$#" -lt 2 ]; then
	echo "usage: $0 [--disassemble] <apk-or-cgp>... <outdir>" >&2
	exit 2
fi

# Last argument is the output directory.
OUTROOT="${*: -1}"
set -- "${@:1:$(($#-1))}"
mkdir -p "$OUTROOT"

for artifact in "$@"; do
	name=$(basename "$artifact")
	name="${name%.*}"
	if [ "$#" -eq 1 ]; then
		dir="$OUTROOT"          # single artifact: dump straight into outdir
	else
		dir="$OUTROOT/$name"    # many: one subdirectory each
	fi
	mkdir -p "$dir"

	if ! ls "$dir"/classes*.dex >/dev/null 2>&1; then
		unzip -q -o "$artifact" 'classes*.dex' -d "$dir" 2>/dev/null
	fi
	if ! ls "$dir"/classes*.dex >/dev/null 2>&1; then
		echo "  $name: no dex found, skipping" >&2
		continue
	fi

	out="$dir/full-dump.txt"
	[ "$DIS" -eq 1 ] && out="$dir/dis.txt"
	if [ ! -s "$out" ]; then
		: > "$out"
		for d in "$dir"/classes*.dex; do
			if [ "$DIS" -eq 1 ]; then
				"$DEXDUMP" -d "$d" >> "$out" 2>/dev/null
			else
				"$DEXDUMP" "$d" >> "$out" 2>/dev/null
			fi
		done
	fi
	printf '%-30s %2s dex  %10s lines -> %s\n' \
		"$name" "$(ls "$dir"/classes*.dex | wc -l | tr -d ' ')" \
		"$(wc -l < "$out" | tr -d ' ')" "$out"
done

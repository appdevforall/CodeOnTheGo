#!/usr/bin/env bash
# Usage: check-release-apk-no-sqlite-debugger.sh <apk>
#
# Fails (exit 1, one line per finding) when an APK carries the SQLite debugger (ADFA-7131):
#   - a lib/<abi>/libsqlitedbg.so entry,
#   - the provider org.appdevforall.sqlitedebugger.DebuggerStartup in the manifest,
#   - a <queries> package com.itsaky.androidide in the manifest,
#   - the class descriptor prefix Lorg/appdevforall/sqlitedebugger/ in any classes*.dex.
# INTERNET is only a WARNING line, not a failure: the app may declare it for itself, and the
# manifest cannot say which declaration it came from. Without the provider above, treat the
# warning as the app's own.
# Exit 2 on bad usage or when aapt2, unzip or the APK cannot be found.
#
# aapt2 comes from $AAPT2, else the newest build-tools under $ANDROID_HOME / $ANDROID_SDK_ROOT
# / ~/Android/Sdk.

set -u
set -o pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
	echo "usage: $0 <apk>" >&2
	exit 2
fi
apk=$1

if ! command -v unzip >/dev/null 2>&1; then
	echo "unzip not found: install it to inspect the APK" >&2
	exit 2
fi

aapt2=${AAPT2:-}
if [ -z "$aapt2" ]; then
	for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
		[ -n "$sdk" ] || continue
		candidate=$(ls -d "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)
		if [ -n "$candidate" ]; then
			aapt2=$candidate
			break
		fi
	done
fi
if [ -z "$aapt2" ] || [ ! -x "$aapt2" ]; then
	echo "aapt2 not found: set AAPT2 or ANDROID_HOME" >&2
	exit 2
fi

manifest=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk" 2>&1) || {
	echo "cannot read the manifest of $apk: $manifest" >&2
	exit 2
}

failed=0
fail() {
	echo "FAIL: $1"
	failed=1
}

libs=$(unzip -Z1 "$apk" | grep -E '^lib/[^/]+/libsqlitedbg\.so$' || true)
for entry in $libs; do
	fail "native library $entry"
done

if grep -q 'org\.appdevforall\.sqlitedebugger\.DebuggerStartup' <<<"$manifest"; then
	fail "manifest declares provider org.appdevforall.sqlitedebugger.DebuggerStartup"
fi

# xmltree indents children deeper than their parent: collect the lines under <queries>, then look
# at the name of its <package> children.
if awk '
	{ match($0, /^ */); indent = RLENGTH }
	/E: queries / { inq = 1; qindent = indent; next }
	inq && indent <= qindent { inq = 0 }
	inq && /E: package / { pkg = 1; next }
	inq && /E: / { pkg = 0 }
	inq && pkg && /:name\(.*"com\.itsaky\.androidide"/ { found = 1 }
	END { exit !found }
' <<<"$manifest"; then
	fail "manifest <queries> lists package com.itsaky.androidide"
fi

# A dex stores type descriptors as plain strings, so a debugger class that got in shows up here even when
# the manifest is clean (a library that registers nothing).
# grep -c reads all its input: with pipefail, grep -q exiting early would make unzip die of SIGPIPE
# and the pipeline report failure.
dex_hits=$(unzip -p "$apk" 'classes*.dex' 2>/dev/null | grep -ac 'Lorg/appdevforall/sqlitedebugger/' || true)
if [ "${dex_hits:-0}" -gt 0 ]; then
	fail "a classes*.dex contains org.appdevforall.sqlitedebugger classes"
fi

if grep -q 'android.permission.INTERNET' <<<"$manifest"; then
	echo "WARN: manifest has android.permission.INTERNET. It comes from the debugger only if the provider DebuggerStartup is also present (that is a FAIL above); otherwise it is the app's own."
fi

if [ "$failed" -eq 0 ]; then
	echo "OK: no SQLite debugger found in $apk"
fi
exit "$failed"

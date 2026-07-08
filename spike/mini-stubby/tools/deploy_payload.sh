#!/bin/sh
# Deploy a built payload apk to the shell app (no install). Usage: deploy_payload.sh <apk>
set -e
. "$(dirname "$0")/env.sh"
APK="$1"
[ -f "$APK" ] || { echo "deploy: no such apk: $APK" >&2; exit 1; }
HOST_PKG="com.adfa.ministubby.host"
"$ADB" push "$APK" /data/local/tmp/ministubby-payload.apk >/dev/null
"$ADB" shell run-as "$HOST_PKG" sh -c \
  '"mkdir -p files/payload && cp /data/local/tmp/ministubby-payload.apk files/payload/.incoming.tmp && mv files/payload/.incoming.tmp files/payload/payload.apk"'

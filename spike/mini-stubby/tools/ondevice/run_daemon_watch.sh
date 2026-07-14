#!/system/bin/sh
# ADFA-4128 — on-device daemon launcher with the daemon's OWN file watcher ENABLED
# (-Dwatch=true). Fully on-device: the phone watches its project files and rebuilds on
# save — no Mac, works in airplane mode. Used for Flow-2 (edit in CoGo → reload) until
# CoGo's own Run→POST wiring (the intended watch=false driver) is finished.
# $1 = open project dir.
D="$(cd "$(dirname "$0")" && pwd)"
PROJ="${1:-/storage/emulated/0/CodeOnTheGoProjects/LemonadeStand}"
exec "/data/user/0/com.itsaky.androidide/files/usr/lib/jvm/java-21-openjdk/bin/java" -Xmx512m \
  --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED \
  -cp "$D/classes:$D/lib/*:/data/user/0/com.itsaky.androidide/files/home/android-sdk/build-tools/35.0.0/lib/d8.jar" \
  -Daapt2="/data/user/0/com.itsaky.androidide/files/home/android-sdk/build-tools/35.0.0/aapt2" -Dserve=true -Dwatch=true \
  -Dsrc="$PROJ/app/src/main/java" -Dres="$PROJ/app/src/main/res" \
  -Dmanifest="$PROJ/app/src/main/AndroidManifest.xml" -Dwork="$D/work" \
  KotlinCompileService "$D" "$PROJ" "/data/user/0/com.itsaky.androidide/files/home/android-sdk/platforms/android-36/android.jar" "$D/lib/kotlin-stdlib-2.0.21.jar" "/data/user/0/com.itsaky.androidide/files/home/android-sdk/build-tools/35.0.0/lib/d8.jar"

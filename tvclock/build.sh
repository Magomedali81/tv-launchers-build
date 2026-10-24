#!/usr/bin/env bash
# Сборка TV Clock стандартными инструментами SDK (без Gradle и сторонних библиотек).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-34/android.jar
rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk
javac -source 17 -target 17 -nowarn -Xlint:-options -encoding UTF-8 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/unsigned.apk build/withdex.apk
(cd build/dex && zip -q ../withdex.apk classes.dex)
"$BT/zipalign" -f 4 build/withdex.apk build/tvclock.apk
echo "built build/tvclock.apk"

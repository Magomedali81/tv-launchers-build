#!/usr/bin/env bash
# Сборка одного лаунчера из закреплённого коммита и подпись нашим ключом.
set -euo pipefail
ID="$1"; ROOT="$(pwd)"
J() { python3 -c "import json,sys;l={x['id']:x for x in json.load(open('lock.json'))['launchers']}['$ID'];print(l.get('$1',''))"; }
REPO=$(J repo); COMMIT=$(J commit); KIND=$(J build); DIR=$(J dir); MOD=$(J module); PREP=$(J prep)
BT="$ANDROID_HOME/build-tools/36.0.0"
rm -rf src && git init -q src && cd src
git remote add origin "https://github.com/$REPO" && git fetch -q --depth 1 origin "$COMMIT" && git checkout -q FETCH_HEAD
test "$(git rev-parse HEAD)" = "$COMMIT" || { echo "Коммит не совпал"; exit 1; }
cd "$DIR"
if [ "$KIND" = "gradle" ]; then
  [ -n "$PREP" ] && bash -c "$PREP"
  # Не используем gradle-wrapper.jar из чужого репозитория — только официальный Gradle.
  gradle --no-daemon -q ":$MOD:assembleRelease"
  APK=$(ls "$MOD"/build/outputs/apk/release/*.apk | head -1)
else
  mkdir -p "$HOME/.android"; [ -f "$HOME/.android/debug.keystore" ] || keytool -genkeypair -q -keystore "$HOME/.android/debug.keystore" -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug" -keyalg RSA -validity 10000
  bash "$(J script)"
  APK="$(J out)"
fi
cd "$ROOT"; mkdir -p out
"$BT/zipalign" -f -p 4 "src/$DIR/$APK" "out/$ID.aligned.apk"
"$BT/apksigner" sign --ks "$KS_FILE" --ks-pass env:KS_PASS --key-pass env:KS_PASS --ks-key-alias tvdoctor --out "out/$ID.apk" "out/$ID.aligned.apk"
"$BT/apksigner" verify --print-certs "out/$ID.apk" | head -3
rm -f "out/$ID.aligned.apk" "out/$ID.apk.idsig"
python3 scripts/scan_apk.py "out/$ID.apk" "$ID" "$BT"
sha256sum "out/$ID.apk" | cut -d' ' -f1 > "out/$ID.apk.sha256"

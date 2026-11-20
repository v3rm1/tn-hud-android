#!/usr/bin/env bash
# Build TN-HUD Live (and optionally the icon pack) without Gradle:
#   javac -> d8 (or dx) -> aapt -> zipalign -> apksigner
#
#   ./build.sh            # wallpaper app  -> dist/TN-HUD-Live.apk
#   ./build.sh icons      # icon pack      -> dist/TN-HUD-Icons.apk
#   ./build.sh link       # notification companion -> dist/TN-HUD-Link.apk
#   ./build.sh all
#
# Environment (defaults in brackets):
#   ANDROID_HOME   Android SDK root            [$ANDROID_SDK_ROOT or ~/Library/Android/sdk or ~/Android/Sdk]
#   BUILD_TOOLS    build-tools version         [newest installed]
#   ANDROID_JAR    compile platform jar        [$ANDROID_HOME/platforms/android-23/android.jar]
#   KEYSTORE       signing keystore            [keystore/tn-hud.jks]
#   KS_PASS        keystore password           [prompted by apksigner if unset]
#   KEY_ALIAS      key alias                   [tnhud]
#
# The app is compiled against API 23 on purpose: anything newer is reached by reflection or through the
# compile-only stubs in app/stubs, so the build never depends on a recent platform jar.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /usr/lib/android-sdk; do [ -d "$d" ] && SDK=$d && break; done
fi
[ -n "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK"; exit 1; }

BT=""
if [ -d "$SDK/build-tools" ]; then
  V=${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}
  BT="$SDK/build-tools/$V"
fi
tool() {   # prefer build-tools, fall back to PATH (Debian packages: aapt, zipalign, apksigner, dalvik-exchange)
  if [ -n "$BT" ] && [ -x "$BT/$1" ]; then echo "$BT/$1"; elif command -v "$1" >/dev/null; then command -v "$1"; else echo ""; fi
}
AAPT=$(tool aapt); ZIPALIGN=$(tool zipalign); APKSIGNER=$(tool apksigner); D8=$(tool d8); DX=$(tool dx)
[ -z "$DX" ] && DX=$(command -v dalvik-exchange || true)
AJ=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
for t in AAPT ZIPALIGN APKSIGNER; do [ -n "${!t}" ] || { echo "missing $t (install Android SDK build-tools)"; exit 1; }; done
[ -f "$AJ" ] || { echo "missing $AJ (sdkmanager \"platforms;android-23\" or set ANDROID_JAR)"; exit 1; }

KEYSTORE=${KEYSTORE:-$ROOT/keystore/tn-hud.jks}
KEY_ALIAS=${KEY_ALIAS:-tnhud}
mkdir -p dist build

sign() {   # $1 unsigned, $2 out
  "$ZIPALIGN" -f 4 "$1" build/aligned.apk
  [ -f "$KEYSTORE" ] || { echo "no keystore at $KEYSTORE (see README: Signing)"; exit 1; }
  if [ -n "${KS_PASS:-}" ]; then
    "$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$KS_PASS" --out "$2" build/aligned.apk
  else
    "$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --out "$2" build/aligned.apk
  fi
  "$APKSIGNER" verify "$2" && echo "built $2"
}

build_app() {
  rm -rf build/classes build/stubs build/dex && mkdir -p build/classes build/stubs build/dex
  javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d build/stubs $(find app/stubs -name '*.java')
  javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -classpath build/stubs -d build/classes $(find app/src -name '*.java')
  if [ -n "$D8" ]; then
    "$D8" --min-api 26 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
  elif [ -n "$DX" ]; then
    "$DX" --dex --output=build/dex/classes.dex build/classes
  else echo "missing d8/dx"; exit 1; fi
  rm -f build/app.apk
  rm -rf build/assets && mkdir -p build/assets/frame
  cp -R app/assets/. build/assets/
  cp icons/res/drawable-nodpi/iconback.png icons/res/drawable-nodpi/iconmask.png icons/res/drawable-nodpi/iconupon.png build/assets/frame/   # launcher icon frames, single source
  "$AAPT" package -f -M app/AndroidManifest.xml -S app/res -A build/assets -I "$AJ" -F build/app.apk -0 ttf
  (cd build/dex && "$AAPT" add ../app.apk classes.dex >/dev/null)
  sign build/app.apk dist/TN-HUD-Live.apk
}

build_icons() {
  rm -f build/icons.apk
  "$AAPT" package -f -M icons/AndroidManifest.xml -S icons/res -A icons/assets -I "$AJ" -F build/icons.apk
  sign build/icons.apk dist/TN-HUD-Icons.apk
}

build_link() {
  rm -rf build/lclasses build/ldex && mkdir -p build/lclasses build/ldex
  javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d build/lclasses $(find link/src -name '*.java')
  if [ -n "$D8" ]; then "$D8" --min-api 26 --lib "$AJ" --output build/ldex $(find build/lclasses -name '*.class')
  else "$DX" --dex --output=build/ldex/classes.dex build/lclasses; fi
  rm -f build/link.apk
  "$AAPT" package -f -M link/AndroidManifest.xml -S link/res -I "$AJ" -F build/link.apk
  (cd build/ldex && "$AAPT" add ../link.apk classes.dex >/dev/null)
  sign build/link.apk dist/TN-HUD-Link.apk
}

case "${1:-app}" in
  app) build_app ;;
  icons) build_icons ;;
  link) build_link ;;
  all) build_app; build_icons; build_link ;;
  *) echo "usage: $0 [app|icons|link|all]"; exit 1 ;;
esac

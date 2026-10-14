#!/usr/bin/env bash
#
# Builds what start.sh needs:
# - the APK of the app, a release build (minified, like the published one) signed with the debug
#   key of this machine, since the release key is not available here;
# - the backend images of the Twake Calendar e2e stack (Sabre CalDAV / CardDAV, the calendar side
#   service, LDAP, Dex), from a twake-calendar-frontend checkout, when they are missing.
#
#   scripts/qa-environment/build.sh    # once, and whenever the code under test changes
#   TWAKE_CALENDAR_DIR=~/src/twake-calendar-frontend scripts/qa-environment/build.sh
#   FORCE_BACKEND_BUILD=true scripts/qa-environment/build.sh   # rebuilds the backend images
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TWAKE_CALENDAR_DIR="${TWAKE_CALENDAR_DIR:-$ROOT/../twake-calendar-frontend}"
FORCE_BACKEND_BUILD="${FORCE_BACKEND_BUILD:-false}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

echo "==> Backend images"
E2E_DIR="$TWAKE_CALENDAR_DIR/e2e"
[ -d "$E2E_DIR/docker" ] ||
  { echo "No Twake Calendar e2e stack in $TWAKE_CALENDAR_DIR: set TWAKE_CALENDAR_DIR" >&2; exit 1; }
# image:Dockerfile, the services start.sh runs (the SPAs and their proxy are left out)
for pair in tcalendar-e2e:Dockerfile.tcalendar ldap-e2e:Dockerfile.ldap \
    tcalendar-dex-e2e:Dockerfile.dex sabre-e2e:Dockerfile.sabre; do
  image="${pair%%:*}"
  if [ "$FORCE_BACKEND_BUILD" = true ] || ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "    building $image"
    docker build --quiet --pull -f "$E2E_DIR/docker/${pair#*:}" -t "$image" "$E2E_DIR" >/dev/null
  else
    echo "    $image already built (FORCE_BACKEND_BUILD=true to rebuild it)"
  fi
done

echo "==> APK"
KEYSTORE="$HOME/.android/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
[ ! -f "$ROOT/key.properties" ] ||
  echo "    key.properties found: the release is signed with the key it names, not the debug key"
cd "$ROOT"
# The signing configuration of app-ose/build.gradle.kts reads these when there is no key.properties
ANDROID_KEYSTORE="$KEYSTORE" ANDROID_KEYSTORE_PASSWORD=android ANDROID_KEY_ALIAS=androiddebugkey \
  ANDROID_KEY_PASSWORD=android ./gradlew app-ose:assembleOseRelease --no-daemon --build-cache
APK="$(ls -t "$ROOT"/app-ose/build/outputs/apk/ose/release/*.apk | head -1)"
echo "==> $APK"

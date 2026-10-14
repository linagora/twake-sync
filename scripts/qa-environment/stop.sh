#!/usr/bin/env bash
#
# Stops the QA environment started by start.sh, drops its data, and leaves the emulator as a run
# found it: the app uninstalled (with its accounts, calendars and contacts), airplane mode off,
# Maestro's driver stopped. The emulator is only reset when the QA stack was running: otherwise
# another run may be using it.
#
#   ANDROID_SERIAL=emulator-5554 scripts/qa-environment/stop.sh
#
set -uo pipefail

QA_PROJECT="${QA_PROJECT:-twake-sync-qa}"
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

RUNNING="$(docker compose -p "$QA_PROJECT" ps -q 2>/dev/null)"
docker compose -p "$QA_PROJECT" down --volumes --remove-orphans
if [ -n "$RUNNING" ] && [ -n "${ANDROID_SERIAL:-}" ] && adb get-state >/dev/null 2>&1; then
  adb uninstall com.twake.android.sync >/dev/null
  adb shell cmd connectivity airplane-mode disable
  adb shell am force-stop dev.mobile.maestro.test
  adb shell am force-stop dev.mobile.maestro
  adb forward --remove-all
fi
echo "==> QA environment '$QA_PROJECT' stopped"

#!/usr/bin/env bash
#
# Starts the QA environment of the Android app: the backend of the Twake Calendar e2e stack
# (Sabre CalDAV / CardDAV, the calendar side service, LDAP), its accounts and seeded data, then the
# APK of build.sh installed afresh on the emulator $ANDROID_SERIAL, with its calendar, contacts
# and notification permissions granted and the first account added. The emulator must already be
# running: this script never starts one.
#
#   ANDROID_SERIAL=emulator-5554 scripts/qa-environment/start.sh
#   ANDROID_SERIAL=emulator-5554 QA_USERS="alice bob carol" scripts/qa-environment/start.sh
#
# Accounts: <uid>@open-paas.org, password "secret" (user name <uid> or <uid>@open-paas.org); the
# app is signed in as the first one. Sabre listens on 127.0.0.1:$QA_DAV_PORT only, which the
# emulator reaches as http://10.0.2.2:$QA_DAV_PORT.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT_DIR="$ROOT/scripts/qa-environment"
TWAKE_CALENDAR_DIR="${TWAKE_CALENDAR_DIR:-$ROOT/../twake-calendar-frontend}"
COMPOSE_FILE="$TWAKE_CALENDAR_DIR/e2e/src/test/resources/docker-twake-calendar-e2e.yml"
QA_PROJECT="${QA_PROJECT:-twake-sync-qa}"
QA_USERS="${QA_USERS:-alice bob}"
export QA_DAV_PORT="${QA_DAV_PORT:-18990}"
NETWORK="${QA_PROJECT}_tcalendar-e2e"
PACKAGE=com.twake.android.sync
IMAGES=(tcalendar-e2e ldap-e2e tcalendar-dex-e2e sabre-e2e)
export ANDROID_SERIAL="${ANDROID_SERIAL:?the emulator under test, e.g. emulator-5554}"
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$HOME/.maestro/bin:$PATH"
export MAESTRO_CLI_NO_ANALYTICS="${MAESTRO_CLI_NO_ANALYTICS:-1}"

compose() {
  docker compose -p "$QA_PROJECT" -f "$COMPOSE_FILE" -f "$SCRIPT_DIR/stack.yml" "$@"
}

# runs curl from inside the stack network, where every service answers to its own name
curl_in_network() {
  docker run --rm -i --network "$NETWORK" curlimages/curl -s "$@"
}

command -v adb >/dev/null || { echo "adb is not on the PATH (Android SDK platform-tools)" >&2; exit 1; }
command -v maestro >/dev/null || { echo "maestro is not on the PATH (https://docs.maestro.dev)" >&2; exit 1; }
[ -f "$COMPOSE_FILE" ] || { echo "No $COMPOSE_FILE: set TWAKE_CALENDAR_DIR" >&2; exit 1; }
APK="$(ls -t "$ROOT"/app-ose/build/outputs/apk/ose/release/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { echo "No APK: run scripts/qa-environment/build.sh first" >&2; exit 1; }
for image in "${IMAGES[@]}"; do
  docker image inspect "$image" >/dev/null 2>&1 ||
    { echo "Missing image $image: run scripts/qa-environment/build.sh first" >&2; exit 1; }
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] ||
  { echo "$ANDROID_SERIAL is not a booted device" >&2; exit 1; }

# The stack goes down again when this script fails after starting it
trap '[ $? = 0 ] || compose down --volumes --remove-orphans >/dev/null 2>&1' EXIT

echo "==> Starting the stack as compose project '$QA_PROJECT'"
# The side service pulls in Sabre, LDAP, Dex, MongoDB... the SPAs and their proxy are not needed
compose up -d twake-calendar-side-service mock-smtp dav-gateway

echo "==> Waiting for the side service"
for _ in $(seq 1 120); do
  if compose logs twake-calendar-side-service 2>/dev/null | grep -q "StartUpChecks all succeeded"; then
    ready=true
    break
  fi
  sleep 5
done
[ "${ready:-false}" = true ] ||
  { echo "The side service did not start within 10 minutes, see: docker compose -p $QA_PROJECT logs" >&2; exit 1; }

echo "==> Creating the QA accounts"
for uid in $QA_USERS; do
  email="$uid@open-paas.org"
  printf 'dn: uid=%s,ou=users,dc=open-paas.org,dc=lng\nobjectClass: inetOrgPerson\nuid: %s\ncn: %s\nsn: %s\ngivenName: %s\nmail: %s\nuserPassword: secret\n' \
      "$uid" "$uid" "${uid^} QA" QA "${uid^}" "$email" \
    | compose exec -T ldap ldapadd -x -H ldap://localhost:389 \
        -D cn=admin,dc=open-paas.org,dc=lng -w admin >/dev/null 2>&1 \
    || echo "    $uid already exists in the directory"
  id="$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  status="$(curl_in_network -o /dev/null -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"firstname\":\"${uid^}\",\"lastname\":\"QA\",\"id\":\"$id\"}" \
    http://twake-calendar-side-service:8000/registeredUsers)"
  case "$status" in
    201) echo "    $email / secret" ;;
    409) echo "    $email / secret (already registered)" ;;
    *) echo "The side service refused to register $email: HTTP $status" >&2; exit 1 ;;
  esac
done

# dav <uid> <curl arguments>: a request to Sabre as <uid>
dav() {
  local uid="$1"
  shift
  curl_in_network -u "$uid@open-paas.org:secret" "$@"
}

# The Sabre id of <uid>: its calendars are /calendars/<id>/, its address books /addressbooks/<id>/
principal_id() {
  dav "$1" -X PROPFIND -H 'Depth: 0' -H 'Content-Type: application/xml' \
      --data '<propfind xmlns="DAV:"><prop><current-user-principal/></prop></propfind>' http://esn_sabre/ \
    | sed -n 's|.*<d:current-user-principal><d:href>/principals/users/\([^/]*\)/.*|\1|p'
}

# put <uid> <path> <content type>, the body on stdin
put() {
  local status
  status="$(dav "$1" -o /dev/null -w '%{http_code}' -X PUT -H "Content-Type: $3" --data-binary @- \
    "http://esn_sabre$2")"
  case "$status" in
    201|204) ;;
    *) echo "Sabre refused PUT $2 as $1: HTTP $status" >&2; exit 1 ;;
  esac
}

echo "==> Seeding the first account"
owner="${QA_USERS%% *}"
guest="$(echo "$QA_USERS" | awk '{print $2}')"
guest_line="${guest:+ATTENDEE;CN=${guest^} QA;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$guest@open-paas.org}"
id="$(principal_id "$owner")"
[ -n "$id" ] || { echo "No principal for $owner" >&2; exit 1; }
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
start="$(date -u -d 'tomorrow 08:00' +%Y%m%dT%H%M%SZ)"
end="$(date -u -d 'tomorrow 09:00' +%Y%m%dT%H%M%SZ)"
put "$owner" "/calendars/$id/$id/qa-seed-weekly.ics" 'text/calendar; charset=utf-8' <<EOF
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Twake QA//seed//EN
BEGIN:VEVENT
UID:qa-seed-weekly
DTSTAMP:$stamp
DTSTART:$start
DTEND:$end
RRULE:FREQ=WEEKLY;COUNT=10
SUMMARY:QA seed: weekly sync
LOCATION:Room 1
DESCRIPTION:Seeded by scripts/qa-environment/start.sh
END:VEVENT
END:VCALENDAR
EOF
start="$(date -u -d 'tomorrow 13:00' +%Y%m%dT%H%M%SZ)"
end="$(date -u -d 'tomorrow 14:00' +%Y%m%dT%H%M%SZ)"
put "$owner" "/calendars/$id/$id/qa-seed-meeting.ics" 'text/calendar; charset=utf-8' <<EOF
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Twake QA//seed//EN
BEGIN:VEVENT
UID:qa-seed-meeting
DTSTAMP:$stamp
DTSTART:$start
DTEND:$end
SUMMARY:QA seed: meeting${guest:+ with $guest}
ORGANIZER;CN=${owner^} QA:mailto:$owner@open-paas.org
ATTENDEE;CN=${owner^} QA;PARTSTAT=ACCEPTED;ROLE=CHAIR:mailto:$owner@open-paas.org
$guest_line
END:VEVENT
END:VCALENDAR
EOF
put "$owner" "/addressbooks/$id/contacts/qa-seed-carol.vcf" 'text/vcard; charset=utf-8' <<EOF
BEGIN:VCARD
VERSION:3.0
UID:qa-seed-carol
FN:Carol Seed
N:Seed;Carol;;;
EMAIL;TYPE=work:carol.seed@example.org
TEL;TYPE=cell:+33 6 12 34 56 78
ORG:Linagora
END:VCARD
EOF
echo "    $owner: 2 events in the default calendar, 1 contact"

echo "==> Installing $(basename "$APK") on $ANDROID_SERIAL"
# Uninstalling drops the accounts of the previous run, and the calendars and contacts they synced
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb install "$APK"
for permission in READ_CALENDAR WRITE_CALENDAR READ_CONTACTS WRITE_CONTACTS POST_NOTIFICATIONS; do
  adb shell pm grant "$PACKAGE" "android.permission.$permission" 2>/dev/null ||
    echo "    android.permission.$permission not granted (not requested by the app on this Android?)"
done

echo "==> Adding the account $owner@open-paas.org"
adb shell am start -W -n "$PACKAGE/at.bitfire.davdroid.ui.setup.LoginActivity" \
  -a android.intent.action.VIEW -d "http://$owner:secret@10.0.2.2:$QA_DAV_PORT/" >/dev/null
maestro --device "$ANDROID_SERIAL" test -e ACCOUNT_NAME="$owner@open-paas.org" "$SCRIPT_DIR/sign-in.yaml"
adb shell am force-stop dev.mobile.maestro.test 2>/dev/null || true

# bounds <text> [<max left>]: "left top right bottom" of the first view of the screen showing
# exactly <text> (whose left edge is below <max left>), nothing when there is none
bounds() {
  adb exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n' |
    grep -F "text=\"$1\"" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' |
    awk -v max="${2:-100000}" '$1 < max {print; exit}'
}

# wait_for <text>: true once <text> is on the screen, within 30 s
wait_for() {
  for _ in $(seq 1 15); do
    [ -n "$(bounds "$1")" ] && return 0
    sleep 2
  done
  return 1
}

# tap <text>: taps the middle of <text>
tap() {
  local b
  read -r -a b <<<"$(bounds "$1")"
  adb shell input tap $(((b[0] + b[2]) / 2)) $(((b[1] + b[3]) / 2))
}

# enable <collection title>: turns on the sync switch at the right end of the collection's row.
# The switch has no text nor id of its own: Maestro cannot select it.
enable() {
  local b width
  wait_for "$1" || { echo "    no collection '$1' on the screen" >&2; return 1; }
  # the title of the collection, at the left of the row, not its type chips
  read -r -a b <<<"$(bounds "$1" 100)"
  width="$(adb shell wm size | sed -n 's/.*: \([0-9]*\)x.*/\1/p' | tail -1)"
  adb shell input tap $((width * 9 / 10)) $(((b[1] + b[3]) / 2))
}

echo "==> Synchronising the default calendar and the contacts of $owner"
# The account screen is open; its collection list may still be empty
wait_for '#default' || { tap 'Refresh list'; wait_for '#default'; } ||
  { echo "The account screen does not list the calendar #default" >&2; exit 1; }
enable '#default'
tap CardDAV
enable contacts
tap 'Synchronize now'
for _ in $(seq 1 30); do
  synced="$(adb shell content query --uri content://com.android.calendar/events --projection title 2>/dev/null |
    grep -c 'QA seed' || true)"
  synced=$((synced + $(adb shell content query --uri content://com.android.contacts/raw_contacts \
    --projection display_name 2>/dev/null | grep -c 'Carol Seed' || true)))
  [ "$synced" -ge 3 ] && break
  sleep 2
done
# Not fatal: a broken sync is for the QA run to report
[ "${synced:-0}" -ge 3 ] && echo "    the seeded events and contact are on the device" ||
  echo "    WARNING: the seeded events and contact did not all reach the device within a minute" >&2

cat <<INFO

==> QA environment '$QA_PROJECT' is up

Twake Sync on $ANDROID_SERIAL is signed in as $owner@open-paas.org (password secret), base URL
http://10.0.2.2:$QA_DAV_PORT/. From this host, the same server is http://localhost:$QA_DAV_PORT/, e.g.:
  curl -s -u $owner:secret -X PROPFIND -H 'Depth: 1' http://localhost:$QA_DAV_PORT/calendars/$id/

Stop it with: ANDROID_SERIAL=$ANDROID_SERIAL scripts/qa-environment/stop.sh
INFO

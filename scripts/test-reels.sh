#!/usr/bin/env bash
# test-reels.sh — logs into the Frianzo native app on the emulator by
# itself (throwaway temp-mail inbox), opens Reels, scrolls 2-3 reels, and
# captures a screen recording + screenshots + logcat into test-output/.
#
# Runs INSIDE the reactivecircus/android-emulator-runner step (the
# emulator is already booted). No `set -e`: every step records a marker
# instead of dying, so evidence is always collected.
#
# Exit codes:
#   0 = flow completed, reels visibly playing (no black screen detected)
#   1 = BLACK SCREEN auto-detected in a screenshot (bug reproduced)
#   2 = flow failed (login/navigation) — needs a human look
#
# Safety: watches reels only. Never likes, comments, posts, shares or
# reports anything. The only production side effect is ONE junk user
# account created with a throwaway email (recorded in
# test-output/test-account.txt so the owner can delete it).

OUT="test-output"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.frianzo.nativeapp"
ACTIVITY="com.frianzo.app.MainActivity"
MAILTM="https://api.mail.tm"
MAILTM_PASS="FrianzoTest123!"

mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# adb text typing: spaces become %s, the rest (@ . digits) passes as-is.
adb_text() {
  local t="${1// /%s}"
  adb shell input text "$t"
}

fail_flow() {
  echo "FLOW_FAIL: $1" | tee "$OUT/RESULT.txt"
  adb exec-out screencap -p > "$OUT/failure.png" 2>/dev/null || true
  python3 scripts/uitap.py dump "$OUT/failure-ui.xml" >/dev/null 2>&1 || true
  exit 2
}

# ---------------------------------------------------------------- boot + install
log "Waiting for emulator..."
adb wait-for-device
for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell getprop sys.boot_completed | tr -d '\r' | grep -q 1 || fail_flow "emulator never finished booting"

[ -f "$APK" ] || fail_flow "APK not found at $APK"
log "Installing APK..."
adb install -r "$APK" || fail_flow "adb install failed"
# The emulator's DNS can lag boot: wait until the backend host resolves
# (otherwise the app shows a blocking "Unable to resolve host" screen).
log "Waiting for emulator network (api.frianzo.online must resolve)..."
for _ in $(seq 1 12); do
  if adb shell ping -c 1 -W 2 api.frianzo.online >/dev/null 2>&1; then log "Emulator network is up."; break; fi
  sleep 5
done
adb shell am start -n "$PKG/$ACTIVITY"
sleep 5

# ------------------------------------------------- throwaway inbox (mail.tm)
log "Creating throwaway inbox on mail.tm..."
DOMAIN=$(curl -s --max-time 20 "$MAILTM/domains" | python3 -c "
import sys, json
d = json.load(sys.stdin)
act = [m['domain'] for m in d.get('hydra:member', []) if m.get('isActive')]
print(act[0] if act else '')")
[ -n "$DOMAIN" ] || fail_flow "mail.tm returned no active domain"

RUN_ID="${GITHUB_RUN_ID:-$$}"
TEST_EMAIL="frianzo-test-${RUN_ID}-$(date +%s)@$DOMAIN"
echo "$TEST_EMAIL" > "$OUT/test-account.txt"
curl -s --max-time 20 -X POST "$MAILTM/accounts" -H 'Content-Type: application/json' \
  -d "{\"address\":\"$TEST_EMAIL\",\"password\":\"$MAILTM_PASS\"}" > /tmp/acct.json
grep -q '"id"' /tmp/acct.json || fail_flow "mail.tm account creation failed: $(cat /tmp/acct.json)"
TOKEN=$(curl -s --max-time 20 -X POST "$MAILTM/token" -H 'Content-Type: application/json' \
  -d "{\"address\":\"$TEST_EMAIL\",\"password\":\"$MAILTM_PASS\"}" | python3 -c "
import sys, json
print(json.load(sys.stdin).get('token', ''))")
[ -n "$TOKEN" ] || fail_flow "mail.tm token failed"
log "Inbox ready: $TEST_EMAIL"

# ---------------------------------------------------------------- login (email OTP)
log "Waiting for login screen..."
if ! python3 scripts/uitap.py wait-text "Enter your email" 90; then
  if python3 scripts/uitap.py has-text "Continue to login" 2>/dev/null; then
    log "Session-check error screen - tapping Continue to login..."
    python3 scripts/uitap.py tap-text "Continue to login" || fail_flow "could not tap Continue to login"
    python3 scripts/uitap.py wait-text "Enter your email" 60 || fail_flow "login screen did not appear after Continue to login"
  else
    fail_flow "login screen did not appear"
  fi
fi
python3 scripts/uitap.py tap-text "Enter your email" || fail_flow "could not tap email field"
adb_text "$TEST_EMAIL"
sleep 1
python3 scripts/uitap.py tap-text "Continue with Email" || fail_flow "could not tap Continue with Email"
log "Code requested. Waiting for OTP field..."
python3 scripts/uitap.py wait-text "Enter 6-digit verification code" 90 || fail_flow "OTP field did not appear"

extract_otp() {
  python3 - "$1" <<'PYEOF'
import sys, json, re, html
msg = json.load(open(sys.argv[1]))
text = msg.get("text") or ""
if not text and msg.get("html"):
    text = re.sub(r"<[^>]+>", " ", msg["html"])
    text = html.unescape(text)
# Prefer a 6-digit number near the word code/OTP/verification.
m = re.search(r"(?i)(?:code|otp|verification)[^\d]{0,40}(\d{6})", text)
if m:
    print(m.group(1))
    sys.exit(0)
m = re.search(r"\b(\d{6})\b", text)
print(m.group(1) if m else "")
PYEOF
}

log "Polling inbox for the OTP mail (up to 150s)..."
OTP=""
for i in $(seq 1 15); do
  sleep 10
  MSG_ID=$(curl -s --max-time 20 "$MAILTM/messages" -H "Authorization: Bearer $TOKEN" | python3 -c "
import sys, json
d = json.load(sys.stdin)
m = d.get('hydra:member', [])
print(m[0]['id'] if m else '')")
  if [ -n "$MSG_ID" ]; then
    curl -s --max-time 20 "$MAILTM/messages/$MSG_ID" -H "Authorization: Bearer $TOKEN" > /tmp/otp-mail.json
    OTP=$(extract_otp /tmp/otp-mail.json)
    [ -n "$OTP" ] && break
  fi
  # One resend if the mail is slow (button becomes available after 30s).
  if [ "$i" = 9 ]; then
    python3 scripts/uitap.py tap-text "Resend code" >/dev/null 2>&1 || true
    log "No mail yet — tapped Resend code once."
  fi
done
[ -n "$OTP" ] || fail_flow "no OTP mail arrived in 150s"
log "OTP received."

python3 scripts/uitap.py tap-text "Enter 6-digit verification code" || fail_flow "could not tap code field"
adb_text "$OTP"
sleep 1
python3 scripts/uitap.py tap-text "Verify & Continue" || fail_flow "could not tap Verify & Continue"

log "Waiting for the feed..."
if ! python3 scripts/uitap.py wait-text "What's on your mind" 60; then
  if ! python3 scripts/uitap.py wait-desc "Reels" 30; then
    fail_flow "feed did not load after login (maybe a profile-setup screen — see failure.png)"
  fi
fi
# Dismiss the one-time notification-permission prompt if it shows up.
sleep 3
if python3 scripts/uitap.py has-text "Don't allow" 2>/dev/null; then
  python3 scripts/uitap.py tap-text "Don't allow" >/dev/null 2>&1 || true
  sleep 2
fi
log "Logged in. Feed is up."

# ---------------------------------------------------------------- reels test
adb logcat -c 2>/dev/null || true
log "Starting screen recording (max 170s)..."
REC_START=$(date +%s)
adb shell screenrecord --time-limit 170 --bit-rate 4000000 /sdcard/reels-test.mp4 &
sleep 3

log "Opening Reels..."
python3 scripts/uitap.py tap-desc "Reels" || fail_flow "could not tap the Reels tab"
sleep 12
adb exec-out screencap -p > "$OUT/reels-1.png"
log "Reel 1 captured. Scrolling to reel 2..."
adb shell input swipe 540 1600 540 600 300
sleep 8
adb exec-out screencap -p > "$OUT/reels-2.png"
log "Reel 2 captured. Scrolling to reel 3..."
adb shell input swipe 540 1600 540 600 300
sleep 8
adb exec-out screencap -p > "$OUT/reels-3.png"
sleep 5

log "Stopping screen recording..."
adb shell pkill -2 screenrecord 2>/dev/null || true
sleep 5
adb pull /sdcard/reels-test.mp4 "$OUT/reels-test.mp4" 2>/dev/null || true
if [ ! -s "$OUT/reels-test.mp4" ]; then
  log "Recording not ready yet — waiting out the 170s limit..."
  ELAPSED=$(( $(date +%s) - REC_START ))
  [ "$ELAPSED" -lt 175 ] && sleep $((175 - ELAPSED))
  adb pull /sdcard/reels-test.mp4 "$OUT/reels-test.mp4" 2>/dev/null || true
fi

log "Collecting logcat..."
adb logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null || true
grep -iE "mediaplayer|videoview|illegalstate|fatal|AndroidRuntime" "$OUT/logcat.txt" > "$OUT/logcat-media.txt" 2>/dev/null || true

# ---------------------------------------------------------------- verdict
log "Running best-effort black-screen check on the screenshots..."
python3 -m pip install --quiet pillow 2>/dev/null || true
VERDICT="INCONCLUSIVE"
if OUT_DIR="$OUT" python3 scripts/check-black.py "$OUT"/reels-*.png > "$OUT/check-black.log" 2>&1; then
  VERDICT=$(python3 -c "import json; print(json.load(open('$OUT/result.json'))['verdict'])" 2>/dev/null || echo INCONCLUSIVE)
fi
log "Auto-check verdict: $VERDICT (see $OUT/check-black.log; the video is the source of truth)"

case "$VERDICT" in
  BLACK_SCREEN_SUSPECTED)
    echo "BLACK_SCREEN: auto-check found a near-black video area in a reels screenshot" | tee "$OUT/RESULT.txt"
    exit 1
    ;;
  *)
    echo "PASS: flow completed, reels screenshots do not look black (verdict: $VERDICT)" | tee "$OUT/RESULT.txt"
    exit 0
    ;;
esac

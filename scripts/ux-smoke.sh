#!/usr/bin/env bash
# UX smoke: unit tests → install → Maestro flows on a connected device.
# All navigation stays in ru.srr.safari (explicit component) — never Chrome via openLink.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /Users/alfa/jdks/temurin-17/bin/java ]]; then
    JAVA_HOME=/Users/alfa/jdks/temurin-17
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
export JAVA_HOME="${JAVA_HOME:-}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$HOME/.maestro/bin:$PATH"
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
export MAESTRO_DRIVER_STARTUP_TIMEOUT="${MAESTRO_DRIVER_STARTUP_TIMEOUT:-120000}"

APP_ID=ru.srr.safari
# Mattermost / Telegram / notes / Chrome steal focus mid-flow on this device
COMPETING_PKGS=(
  com.mattermost.rn
  org.telegram.messenger
  ru.srr.notes
  ru.ok.android
  com.vkontakte.android
  com.android.chrome
  com.chrome.beta
  com.opera.browser
  com.duckduckgo.mobile.android
)

force_stop_competitors() {
  local device="$1"
  for pkg in "${COMPETING_PKGS[@]}"; do
    adb -s "$device" shell am force-stop "$pkg" 2>/dev/null || true
  done
}

prep_device() {
  local device="$1"
  adb -s "$device" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
  adb -s "$device" shell input keyevent 82 2>/dev/null || true
  adb -s "$device" shell wm dismiss-keyguard 2>/dev/null || true
  adb -s "$device" shell settings put system screen_off_timeout 600000 2>/dev/null || true
  adb -s "$device" shell am force-stop "$APP_ID" 2>/dev/null || true
  force_stop_competitors "$device"
}

# Deep-link into OUR activity only (component name — not VIEW chooser / Chrome).
seed_our_app_url() {
  local url="$1"
  adb -s "$DEVICE" shell am start \
    -n "${APP_ID}/.MainActivity" \
    -a android.intent.action.VIEW \
    -d "$url" >/dev/null
  sleep 3
}

seed_if_needed() {
  local flow="$1"
  case "$(basename "$flow")" in
    smoke-google-ai.yaml)
      echo "    seed: weather SERP → click «Режим ИИ» in $APP_ID WebView (CDP)"
      seed_our_app_url 'https://www.google.com/search?q=%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B0&hl=ru'
      sleep 5
      python3 "$ROOT/scripts/click-google-ai-tab.py"
      sleep 2
      ;;
    smoke-gdebenz.yaml)
      echo "    seed: gdebenz.ru → $APP_ID"
      seed_our_app_url 'https://gdebenz.ru/'
      sleep 2
      ;;
  esac
}

echo "==> Unit tests (gate)"
./gradlew :app:testDebugUnitTest

DEVICE="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
if [[ -z "${DEVICE}" ]]; then
  echo "ERROR: no adb device. Plug in the phone (USB debugging) and re-run."
  exit 2
fi
echo "==> Device: $DEVICE"

echo "==> Prep device (wake, competitors, screen timeout)"
prep_device "$DEVICE"

echo "==> Assemble + install debug"
./gradlew :app:installDebug

prep_device "$DEVICE"

run_maestro() {
  local flow="$1"
  local attempt=1
  while true; do
    echo "==> Maestro: $flow (attempt $attempt)"
    force_stop_competitors "$DEVICE"
    adb -s "$DEVICE" shell am force-stop "$APP_ID" 2>/dev/null || true
    adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
    seed_if_needed "$flow"
    if maestro test ${REINSTALL_DRIVER:+--reinstall-driver} "$flow"; then
      REINSTALL_DRIVER=
      return 0
    fi
    if [[ "$attempt" -ge 2 ]]; then
      return 1
    fi
    echo "Maestro failed — reinstalling driver and retrying once"
    REINSTALL_DRIVER=1
    attempt=$((attempt + 1))
  done
}

REINSTALL_DRIVER=1
shopt -s nullglob
flows=(maestro/smoke-*.yaml)
if [[ ${#flows[@]} -eq 0 ]]; then
  echo "ERROR: no maestro/smoke-*.yaml flows found"
  exit 3
fi
for flow in "${flows[@]}"; do
  run_maestro "$flow"
done

echo "OK: UX smoke passed on $DEVICE"
echo "ALL_SMOKE_OK"

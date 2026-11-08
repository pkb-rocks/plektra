#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: $0 <base-version> [--install] [--device <adb-serial>] [--backup | --reinstall] [--env <file>]

Examples:
  $0 26.09
  $0 26.09 --install
  $0 26.10 --install --backup
  $0 26.10 --install --reinstall --env .env
  $0 26.09 --install --device 192.168.178.190:46293
USAGE
}

BASE_VERSION="${1:-}"
INSTALL=false
BACKUP_INSTALL=false
REINSTALL=false
ENV_FILE=
DEVICE_SERIAL="${ADB_SERIAL:-}"

if [ -z "$BASE_VERSION" ] || [[ "$BASE_VERSION" == --* ]]; then
  echo "Missing required <base-version>" >&2
  usage
  exit 1
fi
shift

while [ $# -gt 0 ]; do
  case "$1" in
    --install)
      INSTALL=true
      shift
      ;;
    --backup)
      BACKUP_INSTALL=true
      shift
      ;;
    --reinstall)
      BACKUP_INSTALL=true
      REINSTALL=true
      shift
      ;;
    --env)
      [[ $# -ge 2 ]] || { echo "Missing value for --env" >&2; exit 1; }
      ENV_FILE="$2"
      shift 2
      ;;
    --device)
      if [ $# -lt 2 ]; then
        echo "Missing value for --device" >&2
        usage
        exit 1
      fi
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ "$BACKUP_INSTALL" == true && "$INSTALL" != true ]]; then
  echo "--backup and --reinstall require --install." >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_INFO="$($ROOT_DIR/scripts/nightly-version.sh "$BASE_VERSION")"
TIMESTAMP="$(printf '%s\n' "$VERSION_INFO" | awk -F= '/^timestamp=/{print $2}')"
NIGHTLY_SUFFIX="$(printf '%s\n' "$VERSION_INFO" | awk -F= '/^nightly_suffix=/{print $2}')"
VERSION_CODE="$(printf '%s\n' "$VERSION_INFO" | awk -F= '/^version_code=/{print $2}')"
FULL_VERSION="$(printf '%s\n' "$VERSION_INFO" | awk -F= '/^full_version=/{print $2}')"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/nightly/debug/app-nightly-debug.apk"

resolve_adb() {
  local os
  os="$(uname -s 2>/dev/null || echo unknown)"

  if [ -n "${ADB_PATH:-}" ] && [ -x "${ADB_PATH}" ]; then
    printf '%s\n' "${ADB_PATH}"
    return 0
  fi

  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi

  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [ -n "$sdk_root" ] && [ -x "$sdk_root/platform-tools/adb" ]; then
    printf '%s\n' "$sdk_root/platform-tools/adb"
    return 0
  fi

  case "$os" in
    Darwin)
      if [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        printf '%s\n' "$HOME/Library/Android/sdk/platform-tools/adb"
        return 0
      fi
      ;;
    Linux)
      if [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
        printf '%s\n' "$HOME/Android/Sdk/platform-tools/adb"
        return 0
      fi
      ;;
  esac

  return 1
}

cd "$ROOT_DIR"

./gradlew :app:assembleNightlyDebug \
  -PPLEKTRA_VERSION_NAME="$BASE_VERSION" \
  -PPLEKTRA_NIGHTLY_VERSION_CODE="$VERSION_CODE" \
  -PPLEKTRA_NIGHTLY_VERSION_SUFFIX="-nightly.${NIGHTLY_SUFFIX}"

if [ "$INSTALL" = true ]; then
  ADB_BIN="$(resolve_adb || true)"
  if [ -z "$ADB_BIN" ]; then
    echo "adb not found (set ADB_PATH or ANDROID_HOME/ANDROID_SDK_ROOT)." >&2
    exit 1
  fi

  ADB_DEVICE_ARGS=()
  if [ -n "$DEVICE_SERIAL" ]; then
    ADB_DEVICE_ARGS=(-s "$DEVICE_SERIAL")
  else
    device_count="$($ADB_BIN devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')"
    if [ "$device_count" -eq 0 ]; then
      echo "No adb device detected. Connect a device or pass --device <serial>." >&2
      exit 1
    fi
    if [ "$device_count" -gt 1 ]; then
      echo "Multiple adb devices detected. Pass --device <serial> or set ADB_SERIAL." >&2
      "$ADB_BIN" devices -l
      exit 1
    fi
    DEVICE_SERIAL="$($ADB_BIN devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
    ADB_DEVICE_ARGS=(-s "$DEVICE_SERIAL")
  fi

  PREVIOUS_DEFAULT_IME="$(
    "$ADB_BIN" "${ADB_DEVICE_ARGS[@]}" shell settings get secure default_input_method 2>/dev/null |
      tr -d '\r'
  )"

  if [[ "$BACKUP_INSTALL" == true ]]; then
    HELPER_ARGS=(--device "$DEVICE_SERIAL" --package rocks.pkb.plektra.nightly)
    if [[ -n "$ENV_FILE" ]]; then
      HELPER_ARGS+=(--env "$ENV_FILE")
    fi
    HELPER_ARGS+=(install "$APK_PATH")
    if [[ "$REINSTALL" == true ]]; then
      HELPER_ARGS+=(--reinstall)
    fi
    python3 "$ROOT_DIR/scripts/debug_device.py" "${HELPER_ARGS[@]}"
  else
    "$ADB_BIN" "${ADB_DEVICE_ARGS[@]}" install -r "$APK_PATH"
  fi

  # A package replacement stops the running IME. Some Android builds then keep the
  # fallback keyboard selected even though Plektra remains enabled. Restore Nightly
  # only when it was already the user's selected input method before this install.
  if [[ "$PREVIOUS_DEFAULT_IME" == rocks.pkb.plektra.nightly/* ]]; then
    "$ADB_BIN" "${ADB_DEVICE_ARGS[@]}" shell ime enable "$PREVIOUS_DEFAULT_IME" >/dev/null
    "$ADB_BIN" "${ADB_DEVICE_ARGS[@]}" shell ime set "$PREVIOUS_DEFAULT_IME" >/dev/null
  fi
fi

printf 'full_version=%s\n' "$FULL_VERSION"
printf 'version_code=%s\n' "$VERSION_CODE"
printf 'apk=%s\n' "$APK_PATH"

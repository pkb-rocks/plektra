#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<USAGE
Usage: $0 --serial <adb-serial> --device titan-2-elite|titan-2|pixel-7a [options]

Options:
  --serial <serial>       adb serial of an emulator or physical device (required)
  --device <name>         titan-2-elite, titan-2 or pixel-7a (required)
  --only <state>[,<state>]  capture only these states (names as in sources/catalog.json)
  --out <dir>             output directory (default: build/showcase-capture/<device>/<timestamp>/)
  --help, -h              show this help

The scene table is docs/showcase/capture/scenes.json. Every scene runs in the Markdown notes
editor (NotesActivity) with one synthetic note; existing notes are kept and restored afterwards.
Captures go to build/ first.
Copy reviewed files into docs/showcase/raw/<device>/ by hand.

Requires: adb, python3, ffmpeg, shasum or sha256sum, and the Nightly debug build installed
(scripts/build-nightly-debug.sh 26.10 --install --device <serial>).
USAGE
}

SERIAL=""
DEVICE=""
ONLY=""
OUT_DIR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --serial|--device|--only|--out)
      if [ $# -lt 2 ]; then
        echo "Missing value for $1" >&2
        usage
        exit 1
      fi
      case "$1" in
        --serial) SERIAL="$2" ;;
        --device) DEVICE="$2" ;;
        --only) ONLY="$2" ;;
        --out) OUT_DIR="$2" ;;
      esac
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

die() {
  echo "error: $*" >&2
  exit 1
}

warn() {
  echo "warning: $*" >&2
}

if [ -z "$SERIAL" ]; then
  usage
  die "--serial is required"
fi
case "$DEVICE" in
  titan-2-elite|titan-2|pixel-7a) ;;
  "") usage; die "--device is required" ;;
  *) die "Unknown device: $DEVICE" ;;
esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCENES_FILE="$ROOT_DIR/docs/showcase/capture/scenes.json"
PACKAGE="rocks.pkb.plektra.nightly"
APP_CLASS_PREFIX="rocks.pkb.plektra"
IME_COMPONENT="$PACKAGE/$APP_CLASS_PREFIX.inputmethod.PhysicalKeyboardInputMethodService"
RECEIVER_COMPONENT="$PACKAGE/$APP_CLASS_PREFIX.ShowcaseReceiver"
NOTES_COMPONENT="$PACKAGE/$APP_CLASS_PREFIX.NotesEditorAlias"
HOME_TASK_FLAGS="0x10008000"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

resolve_adb() {
  if [ -n "${ADB_PATH:-}" ] && [ -x "${ADB_PATH}" ]; then
    printf '%s\n' "${ADB_PATH}"
    return 0
  fi
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
  if [ -x "$sdk_root/platform-tools/adb" ]; then
    printf '%s\n' "$sdk_root/platform-tools/adb"
    return 0
  fi
  return 1
}

ADB_BIN="$(resolve_adb || true)"
[ -n "$ADB_BIN" ] || die "adb not found (set ADB_PATH or ANDROID_HOME/ANDROID_SDK_ROOT)."

for command_name in python3 ffmpeg; do
  command -v "$command_name" >/dev/null 2>&1 || die "Missing required command: $command_name"
done
if command -v shasum >/dev/null 2>&1; then
  sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
elif command -v sha256sum >/dev/null 2>&1; then
  sha256_file() { sha256sum "$1" | awk '{print $1}'; }
else
  die "Missing required command: shasum or sha256sum"
fi
[ -f "$SCENES_FILE" ] || die "Scene table not found: $SCENES_FILE"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/capture-showcase.XXXXXX")"
HOST_TOUCHED=0
CONFIG_TOUCHED=0
IME_CHANGED=0
PREVIOUS_IME=""
PREVIOUS_DEMO=""
PREVIOUS_NIGHT=""

# Quote one argument for the device shell. Single quotes keep newlines and apostrophes literal.
squote() {
  local value="$1"
  value=${value//\'/\'\\\'\'}
  printf "'%s'" "$value"
}

# Run a command on the device. Each argument is quoted so JSON and text survive the shell.
dsh() {
  local remote="" arg
  for arg in "$@"; do
    remote="$remote $(squote "$arg")"
  done
  "$ADB_BIN" -s "$SERIAL" shell "${remote# }"
}

restore_device() {
  local status=$?
  set +e
  if [ "$HOST_TOUCHED" = 1 ]; then
    dsh input keyevent KEYCODE_HOME >/dev/null 2>&1
  fi
  if [ "$CONFIG_TOUCHED" = 1 ]; then
    dsh am broadcast -n "$RECEIVER_COMPONENT" --ez restore true >/dev/null 2>&1
  fi
  if [ -n "$PREVIOUS_DEMO" ]; then
    dsh am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1
    if [ "$PREVIOUS_DEMO" = "null" ] || [ -z "$PREVIOUS_DEMO" ]; then
      dsh settings delete global sysui_demo_allowed >/dev/null 2>&1
    else
      dsh settings put global sysui_demo_allowed "$PREVIOUS_DEMO" >/dev/null 2>&1
    fi
  fi
  if [ -n "$PREVIOUS_NIGHT" ]; then
    dsh cmd uimode night "$PREVIOUS_NIGHT" >/dev/null 2>&1
  fi
  if [ "$IME_CHANGED" = 1 ] && [ -n "$PREVIOUS_IME" ] && [ "$PREVIOUS_IME" != "null" ]; then
    dsh ime set "$PREVIOUS_IME" >/dev/null 2>&1
  fi
  rm -rf "$WORK_DIR"
  exit "$status"
}
trap restore_device EXIT

# Compile the scene table for this device into one env file per scene.
python3 - "$SCENES_FILE" "$DEVICE" "$ONLY" "$WORK_DIR" <<'PY'
import json
import os
import shlex
import sys

path, device, only, work = sys.argv[1:5]
with open(path, encoding="utf-8") as handle:
    data = json.load(handle)

profile = data["devices"].get(device)
if profile is None:
    sys.exit(f"Unknown device in scene table: {device}")

scenes = [scene for scene in data["scenes"] if scene["device"] == device]
wanted = [name for name in only.split(",") if name]
if wanted:
    known = {scene["state"] for scene in scenes}
    unknown = [name for name in wanted if name not in known]
    if unknown:
        sys.exit(f"Unknown state(s) for {device}: {', '.join(unknown)}")
    scenes = [scene for scene in scenes if scene["state"] in wanted]
if not scenes:
    sys.exit(f"No scenes for {device}")

crop = profile.get("crop") or {}
device_lines = [
    f"EXPECTED_SCREEN={shlex.quote(profile.get('expected_screen') or '')}",
    f"CROP_W={shlex.quote(str(crop.get('width', '')))}",
    f"CROP_H={shlex.quote(str(crop.get('height', '')))}",
    f"CROP_X={shlex.quote(str(crop.get('x', '')))}",
    f"CROP_Y={shlex.quote(str(crop.get('y', '')))}",
]
with open(os.path.join(work, "device.env"), "w", encoding="utf-8") as handle:
    handle.write("\n".join(device_lines) + "\n")

for index, scene in enumerate(scenes, 1):
    note = scene.get("note", {})
    body = note.get("body", "")
    settings = dict(profile.get("baseline", {}))
    settings.update(scene.get("settings", {}))
    note_json = json.dumps({"title": note.get("title", ""), "body": body, "cursor": len(body)},
                           ensure_ascii=False)
    values = {
        "SCENE_ID": scene["id"],
        "SCENE_STATE": scene["state"],
        "SCENE_THEME": scene.get("theme") or "",
        "SCENE_SETTINGS_JSON": json.dumps(settings, ensure_ascii=False),
        "SCENE_NOTE_JSON": note_json,
        "SCENE_MANUAL": "1" if scene.get("manual") else "0",
        "SCENE_INSTRUCTION": scene.get("instruction", ""),
        "SCENE_CONFIDENCE": scene.get("confidence", ""),
    }
    lines = [f"{name}={shlex.quote(value)}" for name, value in values.items()]
    steps = " ".join(shlex.quote(step) for step in scene.get("steps", []))
    lines.append(f"SCENE_STEPS=({steps})")
    with open(os.path.join(work, f"scene-{index:02d}.env"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
PY

# shellcheck source=/dev/null
. "$WORK_DIR/device.env"

if [ -z "$OUT_DIR" ]; then
  OUT_DIR="$ROOT_DIR/build/showcase-capture/$DEVICE/$TIMESTAMP"
fi
mkdir -p "$OUT_DIR"

# Device checks
state="$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)"
[ "$state" = "device" ] || die "Device $SERIAL is not ready (state: ${state:-unknown})."

pm_path="$(dsh pm path "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
case "$pm_path" in
  package:*) ;;
  *) die "$PACKAGE is not installed. Run: scripts/build-nightly-debug.sh 26.10 --install --device $SERIAL" ;;
esac

version_name="$(dsh dumpsys package "$PACKAGE" | tr -d '\r' | sed -n 's/^ *versionName=//p')"
version_name="${version_name%%$'\n'*}"

wm_output="$(dsh wm size | tr -d '\r')"
override_size="$(printf '%s\n' "$wm_output" | sed -n 's/^Override size: //p')"
physical_size="$(printf '%s\n' "$wm_output" | sed -n 's/^Physical size: //p')"
screen_size="${override_size:-$physical_size}"
if [ -n "$CROP_W" ] && [ "$screen_size" = "${CROP_W}x${CROP_H}" ]; then
  # The device already uses the reference size; do not trim its sides again.
  CROP_X=0
  CROP_Y=0
elif [ -n "$EXPECTED_SCREEN" ] && [ "$screen_size" != "$EXPECTED_SCREEN" ]; then
  die "Display is ${screen_size}; $DEVICE captures expect $EXPECTED_SCREEN. Reset with: adb -s $SERIAL shell wm size reset"
fi

# IME: Nightly must be the selected input method.
PREVIOUS_IME="$(dsh settings get secure default_input_method | tr -d '\r')"
if [[ "$PREVIOUS_IME" != "$IME_COMPONENT" ]]; then
  dsh ime enable "$IME_COMPONENT" >/dev/null
  dsh ime set "$IME_COMPONENT" >/dev/null
  IME_CHANGED=1
  echo "Selected Nightly input method (was: ${PREVIOUS_IME:-none})"
fi

# Clear a configuration left behind by an interrupted run, then take the baseline.
dsh am broadcast -n "$RECEIVER_COMPONENT" --ez restore true >/dev/null 2>&1 || true

PREVIOUS_DEMO="$(dsh settings get global sysui_demo_allowed | tr -d '\r')"
PREVIOUS_NIGHT="$(dsh cmd uimode night | tr -d '\r' | awk '{print $NF}')"
dsh settings put global sysui_demo_allowed 1 >/dev/null
dsh am broadcast -a com.android.systemui.demo -e command enter >/dev/null
dsh am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0910 >/dev/null
dsh am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
dsh am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
dsh am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype 3g -e sims 1 -e level 4 >/dev/null
dsh cmd uimode night no >/dev/null

run_scene() {
  local out
  HOST_TOUCHED=1
  dsh input keyevent KEYCODE_HOME >/dev/null
  # Let Android hide the previous IME surface before applying a different profile.
  local hidden_attempt
  for hidden_attempt in 1 2 3 4 5 6 7 8 9 10; do
    if ! dsh dumpsys input_method | grep 'mInputShown=true' >/dev/null; then break; fi
    sleep 0.2
  done
  # Each scene starts from the saved baseline, so settings from an earlier scene do not leak in.
  if [ "$CONFIG_TOUCHED" = 1 ]; then
    out="$(dsh am broadcast -n "$RECEIVER_COMPONENT" --ez restore true | tr -d '\r')"
    case "$out" in
      *"result=0"*) ;;
      *) die "Could not reset settings before $SCENE_ID: $out" ;;
    esac
  fi
  CONFIG_TOUCHED=1
  local -a settings_args=(am broadcast -n "$RECEIVER_COMPONENT" --es settings "$SCENE_SETTINGS_JSON")
  if [ -n "$SCENE_THEME" ]; then
    settings_args+=(--es theme "$SCENE_THEME")
  fi
  out="$(dsh "${settings_args[@]}" | tr -d '\r')"
  case "$out" in
    *"result=0"*) ;;
    *) die "ShowcaseReceiver rejected settings for $SCENE_ID: $out" ;;
  esac

  out="$(dsh am broadcast -n "$RECEIVER_COMPONENT" --es note "$SCENE_NOTE_JSON" | tr -d '\r')"
  case "$out" in
    *"result=0"*) ;;
    *) die "ShowcaseReceiver rejected the note for $SCENE_ID: $out" ;;
  esac
  out="$(dsh am start -W -f "$HOME_TASK_FLAGS" -n "$NOTES_COMPONENT" | tr -d '\r')"
  case "$out" in
    *Error*) die "Could not start the notes editor for $SCENE_ID: $out" ;;
  esac

  local has_back=0
  local step
  for step in "${SCENE_STEPS[@]+"${SCENE_STEPS[@]}"}"; do
    if [ "$step" = "back" ]; then has_back=1; fi
  done
  if [ "$has_back" = 0 ]; then
    local attempt shown=0
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
      if dsh dumpsys input_method | grep 'mInputShown=true' >/dev/null; then
        shown=1
        break
      fi
      sleep 0.4
    done
    if [ "$shown" = 0 ]; then warn "$SCENE_ID: keyboard did not appear"; fi
  fi
  sleep 1.5

  for step in "${SCENE_STEPS[@]+"${SCENE_STEPS[@]}"}"; do
    case "$step" in
      KEYCODE_*)
        dsh input keyevent "$step" >/dev/null
        sleep 0.25
        ;;
      longpress:*)
        dsh input keyevent --longpress "${step#longpress:}" >/dev/null
        sleep 0.5
        ;;
      combo:*)
        local -a combo
        IFS=+ read -r -a combo <<< "${step#combo:}"
        dsh input keycombination "${combo[@]}" >/dev/null
        sleep 0.5
        ;;
      wait:*)
        local ms="${step#wait:}"
        sleep "$((ms / 1000)).$(printf '%03d' $((ms % 1000)))"
        ;;
      back)
        dsh input keyevent KEYCODE_BACK >/dev/null
        sleep 1
        ;;
      tap:*)
        local point="${step#tap:}"
        dsh input tap "${point%,*}" "${point#*,}" >/dev/null
        sleep 0.5
        ;;
      *)
        die "Unknown step for $SCENE_ID: $step"
        ;;
    esac
  done
  sleep 3

  local screen_tmp="$WORK_DIR/$SCENE_STATE-screen.png"
  "$ADB_BIN" -s "$SERIAL" exec-out screencap -p > "$screen_tmp"
  [ "$(wc -c < "$screen_tmp" | tr -d ' ')" -gt 1000 ] || die "Screencap failed for $SCENE_ID"

  local target="$OUT_DIR/$SCENE_STATE.png"
  if [ -n "$CROP_W" ]; then
    ffmpeg -loglevel error -y -i "$screen_tmp" \
      -vf "crop=$CROP_W:$CROP_H:$CROP_X:$CROP_Y" "$target"
  else
    cp "$screen_tmp" "$target"
  fi
  if [ "$SCENE_STATE" != "r5-config-hidden" ]; then
    local export_result
    export_result="$(dsh am broadcast -n "$RECEIVER_COMPONENT" --ez exportTransparent true | tr -d '\r')"
    if ! printf '%s' "$export_result" | grep -q 'result=0'; then
      die "Transparent IME export failed for $SCENE_ID: $export_result"
    fi
    "$ADB_BIN" -s "$SERIAL" exec-out run-as "$PACKAGE" cat cache/debug-reports/keyboard-transparent.png > "$OUT_DIR/$SCENE_STATE-ime.png"
    "$ADB_BIN" -s "$SERIAL" exec-out run-as "$PACKAGE" cat cache/debug-reports/keyboard-transparent.json > "$OUT_DIR/$SCENE_STATE-ime.json"
  fi
  printf '%s\t%s\t%s\n' "$SCENE_STATE" "$SCENE_ID" "$(sha256_file "$target")" \
    >> "$WORK_DIR/captures.tsv"
  echo "Captured $SCENE_ID -> $target"
}

captured=0
for env_file in "$WORK_DIR"/scene-*.env; do
  [ -f "$env_file" ] || continue
  # shellcheck source=/dev/null
  . "$env_file"
  if [ "$SCENE_MANUAL" = "1" ]; then
    echo "Manual: $SCENE_STATE - $SCENE_INSTRUCTION"
    printf '%s\t%s\n' "$SCENE_STATE" "manual: $SCENE_INSTRUCTION" >> "$WORK_DIR/skipped.tsv"
    continue
  fi
  run_scene
  captured=$((captured + 1))
done

python3 - "$OUT_DIR" "$WORK_DIR" "$DEVICE" "$SERIAL" "$PACKAGE" "$version_name" "$TIMESTAMP" "$screen_size" <<'PY'
import hashlib
import json
import os
import struct
import sys
from datetime import datetime, timezone

out_dir, work, device, serial, package, version, stamp, screen = sys.argv[1:9]


def png_size(path):
    with open(path, "rb") as handle:
        header = handle.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    width, height = struct.unpack(">II", header[16:24])
    return width, height


def read_rows(name):
    path = os.path.join(work, name)
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as handle:
        return [line.rstrip("\n").split("\t") for line in handle if line.strip()]


captures = []
files = {}
for state, scene_id, digest in read_rows("captures.tsv"):
    file_name = f"{state}.png"
    path = os.path.join(out_dir, file_name)
    size = png_size(path)
    captures.append({
        "state": state,
        "file": file_name,
        "scene": scene_id,
        "sha256": digest,
        "width": size[0] if size else None,
        "height": size[1] if size else None,
    })
    files[file_name] = digest
    alpha_name = f"{state}-ime.png"
    if os.path.exists(os.path.join(out_dir, alpha_name)):
        for exported_name in (alpha_name, f"{state}-ime.json"):
            import hashlib
            with open(os.path.join(out_dir, exported_name), "rb") as exported:
                files[exported_name] = hashlib.sha256(exported.read()).hexdigest()
        captures[-1]["transparent_ime"] = alpha_name

skipped = [{"state": state, "reason": reason} for state, reason in read_rows("skipped.tsv")]

manifest = {
    "device": device,
    "serial": serial,
    "package": package,
    "version_name": version,
    "captured_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "run_id": stamp,
    "screen_size": screen,
    "captures": captures,
    "skipped": skipped,
    "files": files,
}
with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, indent=2, ensure_ascii=False)
    handle.write("\n")
PY

echo "Captured $captured scene(s) into $OUT_DIR"
echo "Review the files, then copy the approved ones into docs/showcase/raw/$DEVICE/ by hand."

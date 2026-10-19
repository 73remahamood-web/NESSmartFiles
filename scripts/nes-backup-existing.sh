#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
OUT="$HOME/nes_v01_app_data_backup.tar"
if ! command -v adb >/dev/null;then echo 'Install ADB using: pkg install android-tools';exit 1;fi
if ! adb devices | grep -q 'device$';then echo 'Connect to THIS phone via Android Wireless debugging with adb pair + adb connect first.';exit 1;fi
if ! adb shell run-as ye.nes.smartfiles id >/dev/null 2>&1;then echo 'run-as denied. Keep the old app installed: do not uninstall.';exit 1;fi
echo 'Stopping the old app to checkpoint SQLite WAL before taking a consistent backup.'
adb shell am force-stop ye.nes.smartfiles
echo 'Backing up the private database, chats, and installed models. May exceed 700 MB.'
adb exec-out run-as ye.nes.smartfiles tar -cf - databases files shared_prefs > "$OUT"
[ -s "$OUT" ] || { echo 'Empty backup. STOP.';exit 1; }
tar -tf "$OUT" >/dev/null || { echo 'Backup corrupt. STOP.';exit 1; }
tar -tf "$OUT" | head -25 || true
sha256sum "$OUT" > "$OUT.sha256"
echo "Backup saved: $OUT"
echo 'DO NOT UNINSTALL until you verify this archive is present and readable.'

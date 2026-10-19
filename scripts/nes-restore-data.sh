#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
BACKUP="$HOME/nes_v01_app_data_backup.tar"
[ -f "$BACKUP" ] || { echo "Backup missing: $BACKUP";exit 1; }
sha256sum -c "$BACKUP.sha256" || { echo 'Backup hash mismatch. STOP.';exit 1; }
if ! adb shell run-as ye.nes.smartfiles id >/dev/null 2>&1;then echo 'run-as denied. New installed APK must be debuggable to restore using run-as.';exit 1;fi
adb shell am force-stop ye.nes.smartfiles || true
adb shell -T run-as ye.nes.smartfiles tar -xf - < "$BACKUP"
adb shell am force-stop ye.nes.smartfiles || true
echo 'Files restored. Launch NES Smart Files to trigger the non-destructive database migration.'

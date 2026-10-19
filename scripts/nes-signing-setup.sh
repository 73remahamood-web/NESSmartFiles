#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
REPO='73remahamood-web/NESSmartFiles'
echo 'Preparing a PRIVATE signing key for all future APK updates.'
echo 'This key differs from the ephemeral debug key used for V0.1.'
pkg install -y openjdk-21 openssl gh coreutils
if ! gh auth status >/dev/null 2>&1; then echo 'Please sign in to GitHub: gh auth login';exit 1;fi
KEYDIR="$HOME/.nes-signing";mkdir -p "$KEYDIR";chmod 700 "$KEYDIR"
KEYFILE="$KEYDIR/nes-signing.jks"
PASSFILE="$KEYDIR/password.txt"
if [ ! -f "$KEYFILE" ]; then
  PASS="$(openssl rand -hex 20)"
  printf '%s' "$PASS" > "$PASSFILE";chmod 600 "$PASSFILE"
  keytool -genkeypair -alias nes -keyalg RSA -keysize 3072 -validity 9000 \
    -keystore "$KEYFILE" -storetype PKCS12 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname 'CN=NES Smart Files, OU=NES, O=NES Local AI, L=Yemen, C=YE' -noprompt
else
  if [ ! -f "$PASSFILE" ]; then echo "Key file exists, but password file is missing: $PASSFILE";exit 1;fi
  PASS="$(cat "$PASSFILE")"
fi
chmod 600 "$KEYFILE"
gh secret set NES_KEYSTORE_B64 -R "$REPO" --body "$(base64 -w 0 "$KEYFILE")"
gh secret set NES_KEYSTORE_PASS -R "$REPO" --body "$PASS"
gh secret set NES_KEY_ALIAS -R "$REPO" --body 'nes'
gh secret set NES_KEY_PASS -R "$REPO" --body "$PASS"
unset PASS
printf '\nStable signing key registered as encrypted GitHub Actions secrets.\n'
printf 'PRIVATE key is at %s\n' "$KEYFILE"
printf 'PRIVATE password is at %s\n' "$PASSFILE"
printf 'BACK UP BOTH FILES TO A SAFE PRIVATE LOCATION. DO NOT SHARE THEM OR COMMIT THEM.\n'

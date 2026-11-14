#!/usr/bin/env bash
# Create the keystore used to sign release APKs.
#
# Usage:
#   ./scripts/generate-keystore.sh
#
# Then base64-encode it for the GitHub secret:
#   base64 -w0 signingkey.jks
#
# Add the output as the repository secret SIGNING_KEY.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -f signingkey.jks ]]; then
    echo "signingkey.jks already exists; refusing to overwrite" >&2
    exit 1
fi

STORE_PASSWORD="${KEY_STORE_PASSWORD:-android}"
ALIAS="${ALIAS:-key}"
KEY_PASSWORD="${KEY_PASSWORD:-android}"

keytool -genkeypair \
    -keystore signingkey.jks \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=Anime Extensions, OU=Extensions, O=Akrab, C=US"

echo
echo "Created signingkey.jks"
echo "Store password: $STORE_PASSWORD"
echo "Key alias:      $ALIAS"
echo "Key password:   $KEY_PASSWORD"
echo
echo "Fingerprint (add to repo.json -> meta.signingKeyFingerprint):"
keytool -list -v -keystore signingkey.jks -storepass "$STORE_PASSWORD" \
    | awk '/SHA256:/ { print tolower($2); exit }'
echo
echo "Base64 for the SIGNING_KEY secret:"
base64 -w0 signingkey.jks
echo

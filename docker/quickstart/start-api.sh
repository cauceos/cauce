#!/bin/sh
# Entrypoint of the quickstart's cauce-api container: signs the audit chain from the first start,
# with a key generated here, inside the image — no JDK on the host (see QUICKSTART.md).
#
#   1. Generates an Ed25519 key pair and its one-key registry into $KEYS on the first start, and
#      leaves them untouched afterwards (AuditSigningKeygen --into). $KEYS is a Docker volume of
#      this clone's stack: the private key never reaches the repository, nor leaves the volume.
#   2. Starts the application with that pair and registry configured.
#
# `docker compose down -v` deletes the key together with the database it signed, so a new chain
# always starts under a new key.
set -eu

KEYS=/app/audit-keys
java -cp /app/app.jar \
    -Dloader.main=dev.cauce.governance.audit.signing.AuditSigningKeygen \
    org.springframework.boot.loader.launch.PropertiesLauncher --into "$KEYS"

CAUCE_SECURITY_AUDITSIGNING_PRIVATEKEY="$(cat "$KEYS/private-key")"
CAUCE_SECURITY_AUDITSIGNING_PUBLICKEY="$(cat "$KEYS/public-key")"
CAUCE_SECURITY_AUDITSIGNING_REGISTRYPATH="$KEYS/registry.json"
export CAUCE_SECURITY_AUDITSIGNING_PRIVATEKEY CAUCE_SECURITY_AUDITSIGNING_PUBLICKEY \
    CAUCE_SECURITY_AUDITSIGNING_REGISTRYPATH

exec java -jar /app/app.jar

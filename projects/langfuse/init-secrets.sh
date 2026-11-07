#!/usr/bin/env bash
# One-time: generate every secret langfuse.env refers to and store them in
# Vault at kv/laptop/langfuse. Prints nothing secret. Refuses to overwrite an
# existing entry -- the database and ClickHouse volumes were initialised with
# those passwords, and new ones would lock Langfuse out of its own data.
#
#   ./init-secrets.sh [login-email]
set -euo pipefail

: "${VAULT_ADDR:=https://192.168.0.114:8200}"
: "${VAULT_CACERT:=$HOME/.config/vault/ca.pem}"
export VAULT_ADDR VAULT_CACERT

if vault kv get -mount=kv laptop/langfuse >/dev/null 2>&1; then
  echo "kv/laptop/langfuse already exists; not overwriting." >&2
  exit 1
fi

email=${1:-$(git config user.email)}
h() { openssl rand -hex "$1"; }

jq -n \
  --arg pg "$(h 24)" --arg ch "$(h 24)" --arg rd "$(h 24)" --arg mn "$(h 24)" \
  --arg salt "$(h 32)" --arg enc "$(h 32)" --arg na "$(h 32)" \
  --arg pk "pk-lf-$(h 16)" --arg sk "sk-lf-$(h 24)" \
  --arg em "$email" --arg up "$(h 12)" \
  '{POSTGRES_PASSWORD:$pg, CLICKHOUSE_PASSWORD:$ch, REDIS_AUTH:$rd, MINIO_ROOT_PASSWORD:$mn,
    SALT:$salt, ENCRYPTION_KEY:$enc, NEXTAUTH_SECRET:$na,
    PUBLIC_KEY:$pk, SECRET_KEY:$sk, USER_EMAIL:$em, USER_PASSWORD:$up}' \
  | vault kv put -mount=kv laptop/langfuse - >/dev/null

echo "Stored in kv/laptop/langfuse: $(vault kv get -format=json -mount=kv laptop/langfuse | jq -r '.data.data | keys | join(", ")')"
echo "Login: $email -- password: vault kv get -mount=kv -field=USER_PASSWORD laptop/langfuse"

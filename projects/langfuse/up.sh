#!/usr/bin/env bash
# Start (or update) Langfuse: resolve langfuse.env's Vault references into the
# environment, then `docker compose up -d`. Extra args go to docker compose,
# e.g. `./up.sh --pull always`. Stop it with `docker compose -p langfuse stop`.
set -euo pipefail
cd "$(dirname "$0")"

: "${VAULT_ADDR:=https://192.168.0.114:8200}"
: "${VAULT_CACERT:=$HOME/.config/vault/ca.pem}"
export VAULT_ADDR VAULT_CACERT

while IFS='=' read -r name value; do
  case "$name" in ''|\#*) continue ;; esac
  if [[ "$value" == vault:* ]]; then
    ref=${value#vault:}; path=${ref%%#*}; field=${ref#*#}
    [[ "$ref" == *#* ]] || field=$name
    value=$(vault kv get -field="$field" "$path") || { echo "up.sh: cannot read $path#$field from Vault" >&2; exit 1; }
  fi
  export "$name=$value"
done < langfuse.env

docker compose up -d "$@"
echo "Langfuse: http://localhost:3000 (LAN: http://$(ip -4 -o route get 1.1.1.1 | awk '{print $7}'):3000)"

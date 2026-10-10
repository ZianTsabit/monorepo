#!/usr/bin/env bash
# Load config/<env>.env, fetching any value written as a Vault reference:
#
#   POSTGRES_PASSWORD=vault:secret/ziantsabit/prod/be            field = the variable's name
#   AWS_S3_ACCESS_KEY_ID=vault:secret/ziantsabit/prod/be#ACCESS   field named explicitly
#
# The path is what `vault kv get` takes (KV mount included); VAULT_ADDR and a
# login are only needed when the file has a reference. Plain values pass
# through, so a file can mix both.
#
# Sourced by run.sh. Run directly, it prints the Vault-backed settings as
# KEY=value lines under the app's names, for the Kubernetes Secret:
#
#   ./vault-env.sh prod | kubectl -n ziantsabit-prod create secret generic ziantsabit-be \
#       --from-env-file=/dev/stdin --dry-run=client -o yaml | kubectl apply -f -

# The compose files rename a few settings on their way into the container;
# a host run and the Secret do the same. <app name>:<compose name>.
APP_ALIASES=(
    AWS_S3_ACCESS_KEY_ID:RUSTFS_ACCESS_KEY
    AWS_S3_SECRET_ACCESS_KEY:RUSTFS_SECRET_KEY
    CORS_ALLOWED_ORIGINS:SITE_ORIGINS
    CSRF_TRUSTED_ORIGINS:SITE_ORIGINS
    AWS_S3_PUBLIC_ENDPOINT_URL:MEDIA_ORIGIN
)

# Export config/$1.env with its Vault references resolved. The names that
# came from Vault are left in VAULT_NAMES.
load_env() {
    local file="config/$1.env"
    if [[ ! -f "$file" ]]; then
        echo "$file not found: copy config/example.env to it and fill it in" >&2
        return 1
    fi

    set -a
    # shellcheck source=/dev/null
    source "$file"
    set +a

    VAULT_NAMES=()
    local name ref path field value
    for name in $(compgen -e); do
        [[ "${!name}" == vault:* ]] || continue
        ref="${!name#vault:}"
        path="${ref%%#*}"
        field="$name"
        if [[ "$ref" == *#* ]]; then
            field="${ref#*#}"
        fi
        if ! value="$(vault kv get -field="$field" "$path")"; then
            echo "$name: could not read field $field of $path from Vault" >&2
            return 1
        fi
        export "$name=$value"
        VAULT_NAMES+=("$name")
    done
}

# Give the compose-named settings their app names too. A name the file sets
# itself wins.
export_app_names() {
    local pair app compose
    for pair in "${APP_ALIASES[@]}"; do
        app="${pair%%:*}"
        compose="${pair#*:}"
        if [[ -z "${!app:-}" && -n "${!compose:-}" ]]; then
            export "$app=${!compose}"
        fi
    done
}

# The Vault-backed settings as KEY=value, a compose name replaced by its app
# name(s).
print_vault_env() {
    local name pair out renamed
    for name in "${VAULT_NAMES[@]}"; do
        renamed=()
        for pair in "${APP_ALIASES[@]}"; do
            if [[ "${pair#*:}" == "$name" ]]; then
                renamed+=("${pair%%:*}")
            fi
        done
        if ((${#renamed[@]} == 0)); then
            renamed=("$name")
        fi
        for out in "${renamed[@]}"; do
            if [[ "${!out}" == *$'\n'* ]]; then
                echo "$out: a multi-line value cannot go in an env file" >&2
                return 1
            fi
            printf '%s=%s\n' "$out" "${!out}"
        done
    done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    set -euo pipefail
    cd "$(dirname "$0")"
    case "${1:-}" in
        dev | stg | prod) ;;
        *)
            echo "usage: $0 <dev|stg|prod>" >&2
            exit 2
            ;;
    esac
    load_env "$1"
    export_app_names
    print_vault_env
fi

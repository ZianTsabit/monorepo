#!/usr/bin/env bash
# Run the API with one environment's settings, read from config/<env>.env.
#
#   ./run.sh dev                    ./gradlew bootRun on this machine
#   ./run.sh stg --docker -d        docker compose up --build, detached
#
# Anything after the environment (and --docker) is passed on to bootRun or to
# `docker compose up`. See config/example.env for what a file holds, and
# vault-env.sh for values kept in Vault.
set -euo pipefail

cd "$(dirname "$0")"

usage() {
    echo "usage: $0 <dev|stg|prod> [--docker] [args...]" >&2
    exit 2
}

env="${1:-}"
case "$env" in
    dev | stg | prod) ;;
    *) usage ;;
esac
shift

file="config/$env.env"
# shellcheck source=vault-env.sh
source ./vault-env.sh
load_env "$env"

if [[ "${1:-}" == "--docker" ]]; then
    shift
    # The settings are already exported, Vault references resolved, and
    # Compose interpolates from the environment; /dev/null keeps it from
    # reading a stray .env as well.
    compose=(docker compose --env-file /dev/null -f docker-compose.yml)
    # Staging is meant to behave like production: behind the tunnel, with
    # Secure cookies and the real site's origins.
    if [[ "$env" != dev ]]; then
        compose+=(-f docker-compose.prod.yml)
    fi
    exec "${compose[@]}" up --build "$@"
fi

# The compose files rename a few settings on their way into the container.
# Do the same here, so one file serves both ways of running.
export_app_names

# host.docker.internal is how a container reaches this machine; from this
# machine itself, that is localhost.
for name in POSTGRES_HOST AWS_S3_ENDPOINT_URL; do
    if [[ -n "${!name:-}" ]]; then
        export "$name=${!name//host.docker.internal/localhost}"
    fi
done

for name in POSTGRES_HOST POSTGRES_PASSWORD; do
    if [[ -z "${!name:-}" ]]; then
        echo "$name is empty in $file" >&2
        exit 1
    fi
done

exec ./gradlew bootRun "$@"

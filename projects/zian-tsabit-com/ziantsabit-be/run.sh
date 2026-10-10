#!/usr/bin/env bash
# Run the API with one environment's settings, read from config/<env>.env.
#
#   ./run.sh dev                    ./gradlew bootRun on this machine
#   ./run.sh stg --docker -d        docker compose up --build, detached
#
# Anything after the environment (and --docker) is passed on to bootRun or to
# `docker compose up`. See config/example.env for what a file holds.
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
if [[ ! -f "$file" ]]; then
    echo "$file not found: copy config/example.env to it and fill it in" >&2
    exit 1
fi

if [[ "${1:-}" == "--docker" ]]; then
    shift
    compose=(docker compose --env-file "$file" -f docker-compose.yml)
    # Staging is meant to behave like production: behind the tunnel, with
    # Secure cookies and the real site's origins.
    if [[ "$env" != dev ]]; then
        compose+=(-f docker-compose.prod.yml)
    fi
    exec "${compose[@]}" up --build "$@"
fi

set -a
# shellcheck source=/dev/null
source "$file"
set +a

# The compose files rename a few settings on their way into the container.
# Do the same here, so one file serves both ways of running. A name the file
# sets itself wins.
alias_env() {
    if [[ -z "${!1:-}" && -n "${!2:-}" ]]; then
        export "$1=${!2}"
    fi
}
alias_env AWS_S3_ACCESS_KEY_ID RUSTFS_ACCESS_KEY
alias_env AWS_S3_SECRET_ACCESS_KEY RUSTFS_SECRET_KEY
alias_env CORS_ALLOWED_ORIGINS SITE_ORIGINS
alias_env CSRF_TRUSTED_ORIGINS SITE_ORIGINS
alias_env AWS_S3_PUBLIC_ENDPOINT_URL MEDIA_ORIGIN

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

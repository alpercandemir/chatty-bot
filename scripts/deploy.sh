#!/usr/bin/env bash
set -euo pipefail

sha=${1:?Provide the full commit SHA}
registry_user=${2:?Provide the GitHub registry user}
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "$registry_user" =~ ^[a-zA-Z0-9-]+$ ]] || exit 2

root=/home/ubuntu/chatty-bot
release="$root/releases/$sha"
test -f "$root/.env"
test -f "$release/compose.production.yaml"
exec 9> "$root/deploy.lock"
flock -w 600 9

# The job token arrives on stdin, never in a command argument or the server .env.
registry_config=$(mktemp -d)
trap 'sudo rm -rf -- "$registry_config"' EXIT
sudo docker --config "$registry_config" login ghcr.io --username "$registry_user" --password-stdin > /dev/null

compose() {
    local version=$1
    shift
    sudo env \
        CHATTY_BACKEND_IMAGE="ghcr.io/alpercandemir/chatty-bot-backend:sha-$version" \
        CHATTY_FRONTEND_IMAGE="ghcr.io/alpercandemir/chatty-bot-frontend:sha-$version" \
        docker --config "$registry_config" compose -p chatty-bot \
        --env-file "$root/.env" -f "$root/releases/$version/compose.production.yaml" "$@"
}

previous=''
if [[ -L "$root/current" ]]; then
    previous=$(basename "$(readlink -f "$root/current")")
fi
compose "$sha" config --quiet
compose "$sha" pull
if [[ -n "$previous" ]]; then
    mkdir -p "$root/backups"
    chmod 700 "$root/backups"
    umask 077
    compose "$previous" exec -T database pg_dump -U chatty chatty | gzip > "$root/backups/before-$sha-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
fi

healthy=false
if compose "$sha" up -d --no-build --wait --wait-timeout 360; then
    if curl --fail --silent --show-error --retry 5 --retry-delay 5 --retry-all-errors \
        --resolve chatty.138.2.168.194.sslip.io:443:127.0.0.1 \
        https://chatty.138.2.168.194.sslip.io/api/v1/health; then
        healthy=true
    fi
fi
if [[ "$healthy" != true ]]; then
    echo 'Deployment failed; restoring the previous runtime when available.' >&2
    if [[ -n "$previous" ]]; then
        compose "$previous" up -d --no-build --wait --wait-timeout 360
    fi
    exit 1
fi
ln -sfn "$release" "$root/current.next"
mv -Tf "$root/current.next" "$root/current"
printf '\nDeployed Chatty commit %s\n' "$sha"

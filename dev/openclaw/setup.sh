#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  printf 'OPENCLAW_GATEWAY_TOKEN=%s\n' "$(openssl rand -hex 24)" > .env
  chmod 600 .env
fi

oc() { docker compose run -T --rm --no-deps --entrypoint node gateway dist/index.js "$@"; }

oc onboard --non-interactive --accept-risk --skip-health --mode local \
  --auth-choice skip --gateway-auth token --gateway-token-ref-env OPENCLAW_GATEWAY_TOKEN \
  --gateway-bind lan --skip-channels --no-install-daemon
oc config set gateway.http.endpoints.chatCompletions.enabled true
oc models set anthropic/claude-haiku-5-5
oc config set agents.defaults.thinkingDefault off
oc config set agents.defaults.contextInjection never
oc config set agents.defaults.skills '[]' --strict-json
oc config set tools.profile minimal
oc config set tools.toolSearch.enabled false --strict-json
oc config set tools.codeMode.enabled false --strict-json
oc config set tools.swarm.enabled false --strict-json
oc config set tools.updatePlan false --strict-json
docker compose up -d gateway

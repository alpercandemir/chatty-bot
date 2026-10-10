#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .runtime/backend.pid ]]; then
  read -r chatty_pid < .runtime/backend.pid
  if [[ "$chatty_pid" =~ ^[0-9]+$ ]] && kill -0 "$chatty_pid" 2>/dev/null; then
    chatty_command=$(ps -p "$chatty_pid" -o command=)
    if [[ "$chatty_command" != *"java -jar backend/target/chatty-0.1.0.jar"* ]]; then
      echo 'PID does not belong to the Chatty backend. Refusing to stop it.' >&2
      exit 1
    fi
    kill "$chatty_pid"
    for attempt in {1..50}; do
      kill -0 "$chatty_pid" 2>/dev/null || break
      sleep 0.1
    done
  fi
fi
mkdir -p .runtime
printf '%s\n' "$$" > .runtime/backend.pid
exec scripts/backend.sh

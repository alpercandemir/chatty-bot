#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^(GEMINI_API_KEY|AI_MODEL|AI_MODE|AI_MODEL_MAX_ROUNDS|DATABASE_URL|DATABASE_USER|DATABASE_PASSWORD)= ]] || continue
        export "$line"
    done < .env
fi
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar backend/target/chatty-0.1.0.jar

package com.chatty.scenario;

import tools.jackson.databind.JsonNode;

public record ToolSpec(
        String name,
        String version,
        String classification,
        JsonNode argumentSchema,
        JsonNode resultSchema,
        String authorization,
        long timeoutMs,
        String idempotency) {
    public static ToolSpec of(String name, JsonNode schema) {
        return new ToolSpec(
                name,
                "1.0.0",
                name.startsWith("prepare_") ? "PREPARATION" : "READ",
                schema,
                com.chatty.store.Json.obj(
                        "type",
                        "object",
                        "required",
                        java.util.List.of("message"),
                        "properties",
                        java.util.Map.of("message", Schemas.string()),
                        "additionalProperties",
                        true),
                "SERVER_SESSION_AND_RESOURCE_OWNERSHIP",
                5000,
                name.startsWith("prepare_")
                        ? "SERVER_GENERATED_ACTION_AND_OPERATION_IDS"
                        : "SAFE_READ_RETRY_ONCE");
    }
}

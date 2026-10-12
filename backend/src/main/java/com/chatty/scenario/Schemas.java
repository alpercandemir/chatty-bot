package com.chatty.scenario;

import static com.chatty.store.Json.obj;

import com.chatty.api.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Schemas {

    private Schemas() {}

    public static JsonNode object(Map<String, JsonNode> properties, String... required) {
        return obj(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
                "additionalProperties",
                false);
    }

    public static JsonNode string() {
        return obj("type", "string");
    }

    public static JsonNode fields(String... keys) {
        Map<String, JsonNode> properties = new LinkedHashMap<>();
        for (String fieldName : keys) {
            properties.put(fieldName, string());
        }
        return object(properties, keys);
    }

    public static void validate(JsonNode args, JsonNode schema) {
        if (!args.isObject()) {
            throw invalid();
        }
        for (JsonNode requiredField : schema.path("required")) {
            if (!args.hasNonNull(requiredField.asText())) {
                throw invalid();
            }
        }
        args.fieldNames()
                .forEachRemaining(
                        fieldName -> {
                            JsonNode propertySchema = schema.path("properties").get(fieldName);
                            if (propertySchema == null) {
                                throw invalid();
                            }
                            JsonNode propertyValue = args.get(fieldName);
                            switch (propertySchema.path("type").asText()) {
                                case "string" -> {
                                    if (!propertyValue.isTextual()
                                            || propertyValue.asText().isBlank()
                                            || propertyValue.asText().length() > 3000) {
                                        throw invalid();
                                    }
                                }
                                case "object" -> validate(propertyValue, propertySchema);
                                default -> throw invalid();
                            }
                        });
    }

    private static Problem invalid() {
        return new Problem(422, "VALIDATION_FAILED", "Tool arguments are incomplete or invalid.");
    }
}

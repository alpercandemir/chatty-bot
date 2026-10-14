package com.chatty.store;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class Json {

    public static final JsonMapper M = JsonMapper.builder().build();

    private Json() {}

    public static ObjectNode obj(Object... pairs) {
        ObjectNode node = M.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.set(pairs[i].toString(), M.valueToTree(pairs[i + 1]));
        }
        return node;
    }

    public static ArrayNode arr() {
        return M.createArrayNode();
    }

    public static JsonNode parse(String source) {
        try {
            return M.readTree(source);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }

    public static ObjectNode find(JsonNode nodes, String id) {
        for (JsonNode node : nodes) {
            if (node.path("id").asText().equals(id)) {
                return (ObjectNode) node;
            }
        }
        return null;
    }

    public static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static String str(JsonNode node, String key) {
        return node.path(key).asText("");
    }
}

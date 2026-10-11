package com.chatty.store;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

public final class Json {

    public static final ObjectMapper M = new ObjectMapper();

    private Json() {}

    public static ObjectNode obj(Object... pairs) {
        ObjectNode n = M.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            n.set(pairs[i].toString(), M.valueToTree(pairs[i + 1]));
        }
        return n;
    }

    public static ArrayNode arr() {
        return M.createArrayNode();
    }

    public static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }

    public static ObjectNode find(JsonNode a, String id) {
        for (JsonNode n : a) {
            if (n.path("id").asText().equals(id)) {
                return (ObjectNode) n;
            }
        }
        return null;
    }

    public static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static String str(JsonNode n, String k) {
        return n.path(k).asText("");
    }
}

package com.chatty.model;

import com.fasterxml.jackson.databind.JsonNode;

public record Decision(String tool, JsonNode args, String reply, boolean needsInput) {
    public static Decision call(String tool, JsonNode args) {
        return new Decision(tool, args, null, false);
    }

    public static Decision say(String text, boolean wait) {
        return new Decision(null, null, text, wait);
    }
}

package com.chatty.scenario;

import static com.chatty.store.Json.*;
import static com.chatty.store.WorkspaceStore.event;

import com.chatty.api.Problem;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;

public record ToolContext(
    ObjectNode state,
    ObjectNode conversation,
    ObjectNode run,
    String principal
) {
    public JsonNode propose(
        String type,
        JsonNode args,
        JsonNode before,
        int version,
        String title
    ) {
        for (JsonNode a : state.path("actions")) {
            if (
                a.path("conversationId").asText().equals(str(conversation, "id")) &&
                str(a, "status").equals("PROPOSED")
            ) {
                ((ObjectNode) a).put("status", "SUPERSEDED");
            }
        }
        ObjectNode a = obj(
            "id",
            id("action"),
            "operationId",
            id("op"),
            "conversationId",
            str(conversation, "id"),
            "runId",
            str(run, "id"),
            "principalId",
            principal,
            "type",
            type,
            "args",
            args,
            "before",
            before,
            "expectedVersion",
            version,
            "policyVersion",
            "1.0.0",
            "expiresAt",
            Instant.now().plusSeconds(300).toString(),
            "status",
            "PROPOSED",
            "title",
            title
        );
        state.withArray("actions").add(a);
        conversation.put("actionId", str(a, "id"));
        run.put("status", "WAITING_FOR_CONFIRMATION");
        event(
            run,
            "confirmation",
            "Exact action awaiting confirmation",
            obj("actionId", str(a, "id"), "policyCode", "ALLOWED")
        );
        return obj(
            "action",
            a,
            "message",
            title + " Review the details and choose Confirm or Keep unchanged."
        );
    }

    public boolean fault(String kind, String resource) {
        for (JsonNode n : state.path("faults")) {
            ObjectNode f = (ObjectNode) n;
            if (
                str(f, "fault").equals(kind) &&
                f.path("occurrences").asInt() > 0 &&
                (str(f, "orderId").isBlank() || str(f, "orderId").equals(resource))
            ) {
                f.put("occurrences", f.path("occurrences").asInt() - 1);
                return true;
            }
        }
        return false;
    }

    public ObjectNode ownOrder(String id) {
        ObjectNode o = find(state.path("orders"), id);
        if (o == null || !str(o, "principalId").equals(principal)) {
            throw Problem.missing();
        }
        return o;
    }

    public JsonNode deny(String code, String message) {
        event(run, "policy", message, obj("policyCode", code, "allowed", false));
        return obj("allowed", false, "policyCode", code, "message", message);
    }
}

package com.chatty.scenario;

import static com.chatty.store.Json.find;
import static com.chatty.store.Json.id;
import static com.chatty.store.Json.obj;
import static com.chatty.store.Json.str;
import static com.chatty.store.WorkspaceStore.event;

import com.chatty.api.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

public record ToolContext(
        ObjectNode state, ObjectNode conversation, ObjectNode run, String principal) {
    public JsonNode propose(
            String type, JsonNode args, JsonNode before, int version, String title) {
        for (JsonNode action : state.path("actions")) {
            if (action.path("conversationId").asText().equals(str(conversation, "id"))
                    && str(action, "status").equals("PROPOSED")) {
                ((ObjectNode) action).put("status", "SUPERSEDED");
            }
        }
        ObjectNode action =
                obj(
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
                        title);
        state.withArray("actions").add(action);
        conversation.put("actionId", str(action, "id"));
        run.put("status", "WAITING_FOR_CONFIRMATION");
        event(
                run,
                "confirmation",
                "Exact action awaiting confirmation",
                obj("actionId", str(action, "id"), "policyCode", "ALLOWED"));
        return obj(
                "action",
                action,
                "message",
                title + " Review the details and choose Confirm or Keep unchanged.");
    }

    public boolean fault(String kind, String resource) {
        for (JsonNode record : state.path("faults")) {
            ObjectNode fault = (ObjectNode) record;
            if (str(fault, "fault").equals(kind)
                    && fault.path("occurrences").asInt() > 0
                    && (str(fault, "orderId").isBlank()
                            || str(fault, "orderId").equals(resource))) {
                fault.put("occurrences", fault.path("occurrences").asInt() - 1);
                return true;
            }
        }
        return false;
    }

    public ObjectNode ownOrder(String id) {
        ObjectNode order = find(state.path("orders"), id);
        if (order == null || !str(order, "principalId").equals(principal)) {
            throw Problem.missing();
        }
        return order;
    }

    public JsonNode deny(String code, String message) {
        event(run, "policy", message, obj("policyCode", code, "allowed", false));
        return obj("allowed", false, "policyCode", code, "message", message);
    }
}

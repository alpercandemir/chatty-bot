package com.chatty.harness;

import static com.chatty.store.Json.find;
import static com.chatty.store.Json.id;
import static com.chatty.store.Json.obj;
import static com.chatty.store.Json.str;
import static com.chatty.store.WorkspaceStore.event;

import com.chatty.api.Problem;
import com.chatty.api.Sessions;
import com.chatty.scenario.ScenarioPack;
import com.chatty.scenario.ToolContext;
import com.chatty.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class Actions {

    private final WorkspaceStore store;
    private final Map<String, ScenarioPack> packs = new HashMap<>();

    public Actions(WorkspaceStore store, List<ScenarioPack> scenarioPacks) {
        this.store = store;
        scenarioPacks.forEach(pack -> packs.put(pack.id(), pack));
    }

    public ObjectNode get(Sessions.Session session, String id) {
        return store.read(session.workspace(), state -> own(state, session, id).deepCopy());
    }

    public static ObjectNode own(ObjectNode state, Sessions.Session session, String id) {
        ObjectNode action = find(state.path("actions"), id);
        if (action == null || !str(action, "principalId").equals(session.principal())) {
            throw Problem.missing();
        }
        ObjectNode conversation = find(state.path("conversations"), str(action, "conversationId"));
        if (conversation == null || !str(conversation, "sessionToken").equals(session.token())) {
            throw Problem.missing();
        }
        return action;
    }

    public ObjectNode decide(Sessions.Session session, String id, boolean confirm) {
        return store.change(
                session.workspace(),
                state -> {
                    ObjectNode action = own(state, session, id);
                    ObjectNode conversation =
                            find(state.path("conversations"), str(action, "conversationId"));
                    ObjectNode run = find(state.path("runs"), str(action, "runId"));
                    String status = str(action, "status");
                    if (Set.of("SUCCEEDED", "FAILED", "REJECTED", "UNKNOWN").contains(status)) {
                        return action.deepCopy();
                    }
                    if (!status.equals("PROPOSED")) {
                        throw new Problem(
                                409,
                                "ACTION_NOT_CONFIRMABLE",
                                "This proposal is no longer confirmable.");
                    }
                    if (Instant.parse(str(action, "expiresAt")).isBefore(Instant.now())) {
                        action.put("status", "EXPIRED");
                        run.put("status", "STOPPED");
                        publish(conversation, run, action);
                        return action.deepCopy();
                    }
                    if (!confirm) {
                        action.put("status", "DECLINED");
                        run.put("status", "COMPLETED");
                        publish(conversation, run, action);
                        return action.deepCopy();
                    }
                    if (!str(action, "policyVersion").equals("1.0.0")) {
                        throw new Problem(
                                409,
                                "ACTION_NOT_CONFIRMABLE",
                                "Policy changed. Request a new preview.");
                    }
                    action.put("status", "CONFIRMED");
                    event(
                            run,
                            "confirmation",
                            "Customer confirmed the saved proposal",
                            obj("actionId", id));
                    ToolContext context =
                            new ToolContext(state, conversation, run, session.principal());
                    executeConfirmedAction(action, context, id);
                    run.put("status", "COMPLETED");
                    publish(conversation, run, action);
                    return action.deepCopy();
                });
    }

    private void executeConfirmedAction(ObjectNode action, ToolContext context, String actionId) {
        String resource = str(action.path("args"), "orderId");
        if (context.fault("WRITE_FAILURE_BEFORE_COMMIT", resource)) {
            action.put("status", "FAILED");
            action.put("code", "SIMULATED_WRITE_FAILED");
            action.put(
                    "error", "The simulated system failed before committing. No change was made.");
            return;
        }
        try {
            // Domain validation and mutation happen under the same database row
            // lock.
            JsonNode result = packs.get(str(context.state(), "pack")).execute(action, context);
            action.put("status", "EXECUTING");
            event(
                    context.run(),
                    "execute",
                    "Recorded action dispatched",
                    obj("operationId", str(action, "operationId")));
            action.set("result", result);
            context.state()
                    .withArray("operations")
                    .add(
                            obj(
                                    "id",
                                    str(action, "operationId"),
                                    "actionId",
                                    actionId,
                                    "status",
                                    "SUCCEEDED",
                                    "result",
                                    result));
            if (context.fault("WRITE_TIMEOUT_AFTER_COMMIT", resource)) {
                action.put("status", "UNKNOWN");
                event(
                        context.run(),
                        "verify",
                        "Response lost after dispatch; outcome pending verification",
                        obj("status", "UNKNOWN", "operationId", str(action, "operationId")));
            } else {
                action.put("status", "SUCCEEDED");
            }
        } catch (Problem problem) {
            action.put("status", "REJECTED");
            action.put("code", problem.code);
            action.put("error", problem.getMessage());
        }
    }

    public ObjectNode reconcile(String workspace, String operationId) {
        return store.change(
                workspace,
                state -> {
                    ObjectNode record = find(state.path("operations"), operationId);
                    if (record == null) {
                        throw Problem.missing();
                    }
                    ObjectNode action = find(state.path("actions"), str(record, "actionId"));
                    if (str(action, "status").equals("UNKNOWN")) {
                        action.put("status", str(record, "status"));
                        action.set("result", record.path("result"));
                        publish(
                                find(state.path("conversations"), str(action, "conversationId")),
                                find(state.path("runs"), str(action, "runId")),
                                action);
                    }
                    return action.deepCopy();
                });
    }

    public static String outcome(JsonNode action) {
        String status = str(action, "status");
        if (status.equals("UNKNOWN")) {
            return ("The operation outcome is pending verification. No success is confirmed. Reference: "
                    + str(action, "operationId")
                    + ". You can request support.");
        }
        if (status.equals("PROPOSED")) {
            return "Review the exact action and use the confirmation buttons. Nothing has been changed.";
        }
        if (status.equals("DECLINED")) {
            return "Kept unchanged. You declined this proposal.";
        }
        if (Set.of("EXPIRED", "SUPERSEDED").contains(status)) {
            return ("This proposal is "
                    + status.toLowerCase(Locale.ROOT)
                    + ". Request a new preview.");
        }
        if (!status.equals("SUCCEEDED")) {
            return (action.path("error").asText("This operation has not completed.")
                    + " ["
                    + action.path("code").asText(status)
                    + "]");
        }
        return action.path("result")
                .path("message")
                .asText("Verified operation succeeded. Reference: " + str(action, "operationId"));
    }

    private static void publish(ObjectNode conversation, ObjectNode run, ObjectNode action) {
        String message = outcome(action);
        conversation
                .withArray("messages")
                .add(
                        obj(
                                "id",
                                id("msg"),
                                "role",
                                "assistant",
                                "text",
                                message,
                                "actionId",
                                str(action, "id"),
                                "at",
                                Instant.now().toString()));
        event(
                run,
                "outcome",
                message,
                obj(
                        "status",
                        str(action, "status"),
                        "operationId",
                        str(action, "operationId"),
                        "code",
                        str(action, "code")));
    }
}

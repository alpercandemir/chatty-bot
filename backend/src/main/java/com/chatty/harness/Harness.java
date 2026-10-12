package com.chatty.harness;

import static com.chatty.store.Json.arr;
import static com.chatty.store.Json.find;
import static com.chatty.store.Json.id;
import static com.chatty.store.Json.obj;
import static com.chatty.store.Json.str;
import static com.chatty.store.WorkspaceStore.event;

import com.chatty.api.Problem;
import com.chatty.api.Sessions;
import com.chatty.model.Decision;
import com.chatty.model.GeminiAdapter;
import com.chatty.scenario.ScenarioPack;
import com.chatty.scenario.Schemas;
import com.chatty.scenario.ToolContext;
import com.chatty.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class Harness {

    private static final int MAX_MESSAGE_CHARACTERS = 4000;
    private static final int MAX_CLIENT_MESSAGE_ID_CHARACTERS = 120;
    private static final int MAX_RUNS_PER_MINUTE = 30;
    private static final int MAX_PROCESSING_STEPS = 16;
    private static final int MAX_TOOL_ATTEMPTS = 2;
    private static final int MAX_TOOL_CALLS = 10;
    private static final int LIVE_QUEUE_TIMEOUT_SECONDS = 60;
    private static final Duration MAX_RUN_DURATION = Duration.ofSeconds(120);

    private final WorkspaceStore store;
    private final GeminiAdapter gemini;
    private final Map<String, ScenarioPack> packs = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore live = new Semaphore(1, true);
    private final int rounds;

    public Harness(
            WorkspaceStore store,
            GeminiAdapter gemini,
            List<ScenarioPack> scenarioPacks,
            @Value("${chatty.rounds}") int rounds) {
        this.store = store;
        this.gemini = gemini;
        this.rounds = Math.min(6, Math.max(1, rounds));
        scenarioPacks.forEach(pack -> packs.put(pack.id(), pack));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        store.recover();
    }

    public ObjectNode create(Sessions.Session session, String mode) {
        if (!Set.of("scripted", "live").contains(mode)) {
            throw new Problem(422, "VALIDATION_FAILED", "Select scripted or live mode.");
        }
        return store.change(
                session.workspace(),
                state -> {
                    if (!str(state, "generation").equals(session.generation())) {
                        throw new Problem(
                                401, "SESSION_RESET", "Choose a customer again after reset.");
                    }
                    ObjectNode conversation = createConversation(session, state, mode);
                    state.withArray("conversations").add(conversation);
                    ObjectNode response = conversation.deepCopy();
                    response.remove("sessionToken");
                    return response;
                });
    }

    public static ObjectNode ownConversation(
            ObjectNode state, Sessions.Session session, String id) {
        ObjectNode conversation = find(state.path("conversations"), id);
        if (conversation == null
                || !str(conversation, "sessionToken").equals(session.token())
                || !str(conversation, "principalId").equals(session.principal())) {
            throw Problem.missing();
        }
        return conversation;
    }

    public ObjectNode snapshot(Sessions.Session session, String id) {
        return store.change(
                session.workspace(),
                state -> {
                    ObjectNode conversation = ownConversation(state, session, id);
                    expire(state, conversation);
                    ObjectNode response = conversation.deepCopy();
                    response.remove("sessionToken");
                    ObjectNode run = find(state.path("runs"), str(conversation, "runId"));
                    ObjectNode action = find(state.path("actions"), str(conversation, "actionId"));
                    if (run != null) {
                        response.set("run", publicRun(run));
                    }
                    if (action != null) {
                        response.set("action", action.deepCopy());
                    }
                    return response;
                });
    }

    public static ObjectNode publicRun(ObjectNode run) {
        ObjectNode response = run.deepCopy();
        response.remove(List.of("providerContents", "providerCall", "pendingCalls", "input"));
        return response;
    }

    public static void expire(ObjectNode state, ObjectNode conversation) {
        ObjectNode action = find(state.path("actions"), str(conversation, "actionId"));
        ObjectNode run = find(state.path("runs"), str(conversation, "runId"));
        if (action != null
                && str(action, "status").equals("PROPOSED")
                && Instant.parse(str(action, "expiresAt")).isBefore(Instant.now())) {
            action.put("status", "EXPIRED");
            if (run != null) {
                run.put("status", "STOPPED");
                event(run, "stop", "Confirmation expired", obj("code", "CONFIRMATION_EXPIRED"));
            }
        }
        if (run != null
                && str(run, "status").equals("WAITING_FOR_INPUT")
                && Instant.parse(str(run, "updatedAt")).plusSeconds(900).isBefore(Instant.now())) {
            run.put("status", "STOPPED");
            conversation.remove("task");
            event(run, "stop", "Input wait expired", obj("code", "INPUT_EXPIRED"));
        }
    }

    public ObjectNode message(
            Sessions.Session session, String conversationId, String text, String clientMessageId) {
        if (text.isBlank()
                || text.length() > MAX_MESSAGE_CHARACTERS
                || clientMessageId.isBlank()
                || clientMessageId.length() > MAX_CLIENT_MESSAGE_ID_CHARACTERS) {
            throw new Problem(
                    422,
                    "VALIDATION_FAILED",
                    "Send a message of 1–4000 characters with a client message ID.");
        }
        ObjectNode accepted =
                store.change(
                        session.workspace(),
                        state -> {
                            ObjectNode conversation =
                                    ownConversation(state, session, conversationId);
                            expire(state, conversation);
                            for (JsonNode n : state.path("runs")) {
                                if (str(n, "conversationId").equals(conversationId)
                                        && str(n, "clientMessageId").equals(clientMessageId)) {
                                    return publicRun((ObjectNode) n);
                                }
                            }
                            ObjectNode old = find(state.path("runs"), str(conversation, "runId"));
                            if (old != null
                                    && Set.of("QUEUED", "RUNNING").contains(str(old, "status"))) {
                                throw new Problem(
                                        409,
                                        "RUN_ACTIVE",
                                        "Wait for the active run, or stop the assistant first.");
                            }
                            ObjectNode action =
                                    find(state.path("actions"), str(conversation, "actionId"));
                            boolean typedConfirmation =
                                    text.trim().matches("(?i)(yes|confirm|ok|okay|do it)[.!]?");
                            if (action != null
                                    && str(action, "status").equals("PROPOSED")
                                    && !typedConfirmation) {
                                action.put("status", "SUPERSEDED");
                                if (old != null) {
                                    old.put("status", "STOPPED");
                                }
                            }
                            if (old != null && str(old, "status").equals("WAITING_FOR_INPUT")) {
                                old.put("status", "COMPLETED");
                            }
                            long recentRunCount = 0;
                            for (JsonNode n : state.path("runs")) {
                                if (Instant.parse(str(n, "createdAt"))
                                        .isAfter(Instant.now().minusSeconds(60))) {
                                    recentRunCount++;
                                }
                            }
                            if (recentRunCount >= MAX_RUNS_PER_MINUTE) {
                                throw new Problem(
                                        429,
                                        "SESSION_RATE_LIMIT",
                                        "Please wait before sending more messages.");
                            }
                            ObjectNode run =
                                    createQueuedRun(
                                            conversation, conversationId, text, clientMessageId);
                            conversation
                                    .withArray("messages")
                                    .add(
                                            obj(
                                                    "id",
                                                    clientMessageId,
                                                    "role",
                                                    "user",
                                                    "text",
                                                    text,
                                                    "at",
                                                    Instant.now().toString()));
                            conversation.put("runId", str(run, "id"));
                            state.withArray("runs").add(run);
                            event(
                                    run,
                                    "understand",
                                    "Request received",
                                    obj("mode", str(conversation, "mode"), "packVersion", "1.0.0"));
                            if (typedConfirmation) {
                                reply(
                                        conversation,
                                        run,
                                        "Use the Confirm button on the exact action card. A typed reply cannot authorize a business change.",
                                        action != null && str(action, "status").equals("PROPOSED")
                                                ? "WAITING_FOR_CONFIRMATION"
                                                : "COMPLETED");
                            }
                            return publicRun(run);
                        });
        if (str(accepted, "status").equals("QUEUED")) {
            workers.submit(() -> process(session, conversationId, str(accepted, "id")));
        }
        return accepted;
    }

    private ObjectNode createConversation(Sessions.Session session, ObjectNode state, String mode) {
        ObjectNode conversation = obj("id", id("conv"));
        conversation.put("sessionToken", session.token());
        conversation.put("principalId", session.principal());
        conversation.put("pack", str(state, "pack"));
        conversation.put("packVersion", "1.0.0");
        conversation.put("mode", mode);
        conversation.set("messages", arr());
        conversation.put("createdAt", Instant.now().toString());
        return conversation;
    }

    private ObjectNode createQueuedRun(
            ObjectNode conversation, String conversationId, String text, String clientMessageId) {
        ObjectNode run = obj("id", id("run"));
        run.put("conversationId", conversationId);
        run.put("clientMessageId", clientMessageId);
        run.put("input", text);
        run.put("mode", str(conversation, "mode"));
        run.put("status", "QUEUED");
        run.put("packVersion", "1.0.0");
        run.put("policyVersion", "1.0.0");
        run.put("toolSchemaVersion", "1.0.0");
        run.put(
                "model",
                str(conversation, "mode").equals("live") ? gemini.model : "scripted-rehearsal-v1");
        run.put("modelRequests", 0);
        run.put("toolCalls", 0);
        run.put("activeMs", 0);
        run.put("createdAt", Instant.now().toString());
        run.put("updatedAt", Instant.now().toString());
        run.set("events", arr());
        run.set("outputs", arr());
        run.putNull("usage");
        return run;
    }

    private record ProcessingContext(
            Sessions.Session session,
            String conversationId,
            String runId,
            boolean live,
            long startedNanos) {}

    private void process(Sessions.Session session, String conversationId, String runId) {
        boolean acquired = false;
        long start = 0;
        try {
            boolean isLive =
                    store.read(
                            session.workspace(),
                            state -> str(find(state.path("runs"), runId), "mode").equals("live"));
            if (isLive) {
                if (!live.tryAcquire(LIVE_QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new Problem(
                            429,
                            "LIVE_QUEUE_TIMEOUT",
                            "Live AI is busy. Retry later or choose rehearsal.");
                }
                acquired = true;
            }
            start = System.nanoTime();
            ProcessingContext processing =
                    new ProcessingContext(session, conversationId, runId, isLive, start);
            boolean started = startRun(session, runId);
            if (!started) {
                return;
            }
            for (int step = 0; step < MAX_PROCESSING_STEPS; step++) {
                ObjectNode snapshot = processingSnapshot(processing);
                ObjectNode run = (ObjectNode) snapshot.get("run");
                if (!str(run, "status").equals("RUNNING")) {
                    return;
                }
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        > MAX_RUN_DURATION.toMillis()) {
                    throw new Problem(
                            408,
                            "RUN_TIME_LIMIT",
                            "The active processing deadline was reached. No unconfirmed change was executed.");
                }
                ScenarioPack pack = packs.get(str(snapshot, "pack"));
                Decision decision = nextDecision(processing, pack, snapshot, run);
                if (decision.tool() == null) {
                    completeReply(processing, decision);
                    return;
                }
                JsonNode output = executeToolWithRetry(processing, pack, decision);
                if (output.path("stopped").asBoolean()) {
                    return;
                }
                boolean paused = recordToolOutput(processing, decision, output);
                if (paused) {
                    return;
                }
            }
            throw new Problem(
                    429,
                    "MODEL_ROUND_LIMIT",
                    "The model-round limit was reached. Please narrow your request.");
        } catch (Exception e) {
            Problem p =
                    e instanceof Problem
                            ? (Problem) e
                            : new Problem(
                                    500,
                                    "RUN_FAILED",
                                    "The run could not complete. Saved business state is preserved.");
            store.change(
                    session.workspace(),
                    state -> {
                        ObjectNode run = find(state.path("runs"), runId);
                        if (run != null
                                && Set.of("RUNNING", "QUEUED").contains(str(run, "status"))) {
                            event(run, "error", p.getMessage(), obj("code", p.code));
                            reply(
                                    find(state.path("conversations"), conversationId),
                                    run,
                                    p.getMessage(),
                                    "FAILED");
                        }
                        return null;
                    });
        } finally {
            if (acquired) {
                live.release();
            }
        }
    }

    private boolean startRun(Sessions.Session session, String runId) {
        return store.change(
                session.workspace(),
                state -> {
                    ObjectNode run = find(state.path("runs"), runId);
                    if (!str(run, "status").equals("QUEUED")) {
                        return false;
                    }
                    run.put("status", "RUNNING");
                    run.put("startedAt", Instant.now().toString());
                    run.put("deadlineAt", Instant.now().plus(MAX_RUN_DURATION).toString());
                    run.put(
                            "queueMs",
                            Duration.between(Instant.parse(str(run, "createdAt")), Instant.now())
                                    .toMillis());
                    return true;
                });
    }

    private ObjectNode processingSnapshot(ProcessingContext processing) {
        return store.read(
                processing.session().workspace(),
                state ->
                        obj(
                                "pack",
                                str(state, "pack"),
                                "conversation",
                                find(state.path("conversations"), processing.conversationId())
                                        .deepCopy(),
                                "run",
                                find(state.path("runs"), processing.runId()).deepCopy()));
    }

    private Decision nextDecision(
            ProcessingContext processing, ScenarioPack pack, ObjectNode snapshot, ObjectNode run) {
        Decision decision;
        if (processing.live()) {
            if (run.withArray("pendingCalls").isEmpty()
                    && run.path("modelRequests").asInt() >= rounds) {
                throw new Problem(
                        429,
                        "MODEL_ROUND_LIMIT",
                        "The model-round limit was reached. Please narrow your request.");
            }
            boolean modelRequest = run.withArray("pendingCalls").isEmpty();
            store.change(
                    processing.session().workspace(),
                    state -> {
                        ObjectNode current = find(state.path("runs"), processing.runId());
                        if (modelRequest) {
                            current.put("modelRequests", current.path("modelRequests").asInt() + 1);
                            event(
                                    current,
                                    "model",
                                    "Gemini selecting the next permitted step",
                                    obj("model", gemini.model));
                        }
                        return null;
                    });
            try {
                decision = gemini.next(pack, (ObjectNode) snapshot.get("conversation"), run);
            } catch (Problem problem) {
                store.change(
                        processing.session().workspace(),
                        state -> {
                            if (run.has("providerError")) {
                                find(state.path("runs"), processing.runId())
                                        .set("providerError", run.get("providerError"));
                            }
                            if (run.has("providerCauseType")) {
                                find(state.path("runs"), processing.runId())
                                        .set("providerCauseType", run.get("providerCauseType"));
                            }
                            return null;
                        });
                throw problem;
            }
            store.change(
                    processing.session().workspace(),
                    state -> {
                        ObjectNode current = find(state.path("runs"), processing.runId());
                        for (String k :
                                List.of(
                                        "providerContents",
                                        "providerCall",
                                        "pendingCalls",
                                        "usage",
                                        "providerUsage",
                                        "providerError")) {
                            if (run.has(k)) {
                                current.set(k, run.get(k));
                            }
                        }
                        return null;
                    });
        } else {
            decision =
                    store.change(
                            processing.session().workspace(),
                            state ->
                                    pack.rehearse(
                                            find(
                                                    state.path("conversations"),
                                                    processing.conversationId()),
                                            find(state.path("runs"), processing.runId())));
        }
        return decision;
    }

    private void completeReply(ProcessingContext processing, Decision decision) {
        store.change(
                processing.session().workspace(),
                state -> {
                    ObjectNode current = find(state.path("runs"), processing.runId());
                    if (str(current, "status").equals("RUNNING")) {
                        ObjectNode conversation =
                                find(state.path("conversations"), processing.conversationId());
                        current.put("responseOrigin", processing.live() ? "MODEL" : "SCRIPTED");
                        reply(
                                conversation,
                                current,
                                decision.reply(),
                                decision.needsInput() ? "WAITING_FOR_INPUT" : "COMPLETED");
                    }
                    return null;
                });
    }

    private JsonNode executeToolWithRetry(
            ProcessingContext processing, ScenarioPack pack, Decision decision) {
        JsonNode result = null;
        for (int attempt = 0; attempt < MAX_TOOL_ATTEMPTS; attempt++) {
            final int index = attempt;
            result =
                    store.change(
                            processing.session().workspace(),
                            state -> {
                                ObjectNode current = find(state.path("runs"), processing.runId());
                                ObjectNode conversation =
                                        find(
                                                state.path("conversations"),
                                                processing.conversationId());
                                if (!str(current, "status").equals("RUNNING")) {
                                    return obj("stopped", true);
                                }
                                int count = current.path("toolCalls").asInt();
                                if (count >= MAX_TOOL_CALLS) {
                                    return obj(
                                            "error",
                                            obj("code", "TOOL_LIMIT"),
                                            "message",
                                            "Tool-call limit reached.");
                                }
                                current.put("toolCalls", count + 1);
                                event(
                                        current,
                                        "tool",
                                        index == 0
                                                ? "Calling " + decision.tool()
                                                : "Retrying read once",
                                        obj(
                                                "tool",
                                                decision.tool(),
                                                "args",
                                                redact(decision.args()),
                                                "attempt",
                                                index + 1));
                                try {
                                    JsonNode schema = pack.schemas().get(decision.tool());
                                    if (schema == null) {
                                        throw new Problem(
                                                422,
                                                "TOOL_NOT_ALLOWED",
                                                "This tool is not allowed for the active pack.");
                                    }
                                    Schemas.validate(decision.args(), schema);
                                    JsonNode toolResult =
                                            pack.invoke(
                                                    decision.tool(),
                                                    decision.args(),
                                                    new ToolContext(
                                                            state,
                                                            conversation,
                                                            current,
                                                            processing.session().principal()));
                                    if (!toolResult.isObject()
                                            || !toolResult.path("message").isTextual()) {
                                        throw new Problem(
                                                502,
                                                "TOOL_RESULT_INVALID",
                                                "The adapter returned an invalid result; no completion is confirmed.");
                                    }
                                    event(
                                            current,
                                            "evidence",
                                            "Tool result recorded",
                                            obj(
                                                    "tool",
                                                    decision.tool(),
                                                    "result",
                                                    redact(toolResult)));
                                    return toolResult.deepCopy();
                                } catch (Problem e) {
                                    event(
                                            current,
                                            e.code.equals("RESOURCE_NOT_AVAILABLE")
                                                    ? "policy"
                                                    : "error",
                                            e.getMessage(),
                                            obj(
                                                    "tool",
                                                    decision.tool(),
                                                    "code",
                                                    e.code,
                                                    "retryable",
                                                    e.status == 503));
                                    return obj(
                                            "error",
                                            obj("code", e.code, "retryable", e.status == 503),
                                            "message",
                                            e.getMessage());
                                }
                            });
            if (!result.path("error").path("retryable").asBoolean()
                    || attempt == MAX_TOOL_ATTEMPTS - 1) {
                break;
            }
        }
        return result;
    }

    private boolean recordToolOutput(
            ProcessingContext processing, Decision decision, JsonNode output) {
        return store.change(
                processing.session().workspace(),
                state -> {
                    ObjectNode current = find(state.path("runs"), processing.runId());
                    ObjectNode conversation =
                            find(state.path("conversations"), processing.conversationId());
                    current.withArray("outputs")
                            .add(obj("tool", decision.tool(), "result", output));
                    if (processing.live()) {
                        gemini.result(current, decision.tool(), output);
                    }
                    current.put(
                            "activeMs",
                            TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - processing.startedNanos()));
                    current.put("updatedAt", Instant.now().toString());
                    current.put("responseOrigin", "BACKEND");
                    if (str(current, "status").equals("WAITING_FOR_CONFIRMATION")) {
                        reply(
                                conversation,
                                current,
                                str(output, "message"),
                                "WAITING_FOR_CONFIRMATION");
                        return true;
                    }
                    if (output.has("error")) {
                        if (processing.live()
                                && Set.of("VALIDATION_FAILED", "TOOL_NOT_ALLOWED")
                                        .contains(output.path("error").path("code").asText())
                                && current.path("argumentRepairs").asInt() == 0) {
                            current.put("argumentRepairs", 1);
                            event(
                                    current,
                                    "repair",
                                    "One model argument repair permitted",
                                    obj("code", output.path("error").path("code")));
                            return false;
                        }
                        reply(
                                conversation,
                                current,
                                str(output, "message"),
                                output.path("error")
                                                .path("code")
                                                .asText()
                                                .equals("RESOURCE_NOT_AVAILABLE")
                                        ? "COMPLETED"
                                        : "FAILED");
                        return true;
                    }
                    if (output.has("policyCode")
                            || output.has("action")
                            || (output.has("sources") && output.path("sources").isEmpty())) {
                        reply(conversation, current, str(output, "message"), "COMPLETED");
                        return true;
                    }
                    if (!processing.live() && decision.tool().equals("list_orders")) {
                        reply(conversation, current, str(output, "message"), "WAITING_FOR_INPUT");
                        return true;
                    }
                    return !str(current, "status").equals("RUNNING");
                });
    }

    public static JsonNode redact(JsonNode n) {
        JsonNode copy = n.deepCopy();
        if (copy.isObject()) {
            ObjectNode o = (ObjectNode) copy;
            List<String> keys = new ArrayList<>();
            o.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if (Set.of(
                                "line1",
                                "postalCode",
                                "contactEmail",
                                "email",
                                "summary",
                                "conversationSummary",
                                "contactPreference",
                                "message")
                        .contains(k)) {
                    o.put(k, "[redacted]");
                } else {
                    o.set(k, redact(o.get(k)));
                }
            }
        } else if (copy.isArray()) {
            ArrayNode elements = (ArrayNode) copy;
            for (int i = 0; i < elements.size(); i++) {
                elements.set(i, redact(elements.get(i)));
            }
        }
        return copy;
    }

    private static void reply(ObjectNode conversation, ObjectNode run, String text, String status) {
        ObjectNode message =
                obj(
                        "id",
                        id("msg"),
                        "role",
                        "assistant",
                        "text",
                        text,
                        "at",
                        Instant.now().toString(),
                        "runId",
                        str(run, "id"),
                        "responseOrigin",
                        run.path("responseOrigin").asText("BACKEND"));
        ArrayNode sources = arr();
        Set<String> seen = new HashSet<>();
        for (JsonNode output : run.withArray("outputs")) {
            for (JsonNode source : output.path("result").path("sources")) {
                if (seen.add(str(source, "id"))) {
                    sources.add(source.deepCopy());
                }
            }
        }
        if (!sources.isEmpty()) {
            message.set("sources", sources);
        }
        conversation.withArray("messages").add(message);
        run.put("status", status);
        run.put("updatedAt", Instant.now().toString());
        if (run.has("startedAt")) {
            run.put(
                    "activeMs",
                    Duration.between(Instant.parse(str(run, "startedAt")), Instant.now())
                            .toMillis());
        }
        event(
                run,
                "finish",
                status.equals("WAITING_FOR_INPUT")
                        ? "Waiting for customer information"
                        : status.equals("WAITING_FOR_CONFIRMATION")
                                ? "Waiting for exact confirmation"
                                : "Run " + status.toLowerCase(Locale.ROOT),
                obj("status", status));
    }

    public ObjectNode run(Sessions.Session session, String id) {
        return store.read(
                session.workspace(),
                state -> {
                    ObjectNode run = find(state.path("runs"), id);
                    if (run == null) {
                        throw Problem.missing();
                    }
                    ownConversation(state, session, str(run, "conversationId"));
                    return publicRun(run);
                });
    }

    public ObjectNode stop(Sessions.Session session, String id) {
        return store.change(
                session.workspace(),
                state -> {
                    ObjectNode run = find(state.path("runs"), id);
                    if (run == null) {
                        throw Problem.missing();
                    }
                    ObjectNode conversation =
                            ownConversation(state, session, str(run, "conversationId"));
                    if (Set.of("QUEUED", "RUNNING", "WAITING_FOR_INPUT", "WAITING_FOR_CONFIRMATION")
                            .contains(str(run, "status"))) {
                        run.put("status", "STOPPED");
                        ObjectNode action =
                                find(state.path("actions"), str(conversation, "actionId"));
                        if (action != null && str(action, "status").equals("PROPOSED")) {
                            action.put("status", "SUPERSEDED");
                        }
                        event(
                                run,
                                "stop",
                                "Assistant stopped; dispatched operations are not undone",
                                obj());
                    }
                    return publicRun(run);
                });
    }
}

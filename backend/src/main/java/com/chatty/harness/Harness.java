package com.chatty.harness;

import static com.chatty.store.Json.*;
import static com.chatty.store.WorkspaceStore.event;

import com.chatty.api.*;
import com.chatty.model.*;
import com.chatty.scenario.*;
import com.chatty.store.WorkspaceStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class Harness {

    private final WorkspaceStore store;
    private final GeminiAdapter gemini;
    private final Map<String, ScenarioPack> packs = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore live = new Semaphore(1, true);
    private final int rounds;

    public Harness(
        WorkspaceStore s,
        GeminiAdapter g,
        List<ScenarioPack> ps,
        @Value("${chatty.rounds}") int rounds
    ) {
        store = s;
        gemini = g;
        this.rounds = Math.min(6, Math.max(1, rounds));
        ps.forEach(p -> packs.put(p.id(), p));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        store.recover();
    }

    public ObjectNode create(Sessions.Session session, String mode) {
        if (!Set.of("scripted", "live").contains(mode)) {
            throw new Problem(422, "VALIDATION_FAILED", "Select scripted or live mode.");
        }
        return store.change(session.workspace(), s -> {
            if (!str(s, "generation").equals(session.generation())) {
                throw new Problem(401, "SESSION_RESET", "Choose a customer again after reset.");
            }
            ObjectNode c = obj(
                "id",
                id("conv"),
                "sessionToken",
                session.token(),
                "principalId",
                session.principal(),
                "pack",
                str(s, "pack"),
                "packVersion",
                "1.0.0",
                "mode",
                mode,
                "messages",
                arr(),
                "createdAt",
                Instant.now().toString()
            );
            s.withArray("conversations").add(c);
            ObjectNode out = c.deepCopy();
            out.remove("sessionToken");
            return out;
        });
    }

    public static ObjectNode ownConversation(ObjectNode s, Sessions.Session session, String id) {
        ObjectNode c = find(s.path("conversations"), id);
        if (
            c == null ||
            !str(c, "sessionToken").equals(session.token()) ||
            !str(c, "principalId").equals(session.principal())
        ) {
            throw Problem.missing();
        }
        return c;
    }

    public ObjectNode snapshot(Sessions.Session session, String id) {
        return store.change(session.workspace(), s -> {
            ObjectNode c = ownConversation(s, session, id);
            expire(s, c);
            ObjectNode out = c.deepCopy();
            out.remove("sessionToken");
            ObjectNode r = find(s.path("runs"), str(c, "runId")),
                a = find(s.path("actions"), str(c, "actionId"));
            if (r != null) {
                out.set("run", publicRun(r));
            }
            if (a != null) {
                out.set("action", a.deepCopy());
            }
            return out;
        });
    }

    public static ObjectNode publicRun(ObjectNode r) {
        ObjectNode out = r.deepCopy();
        out.remove(List.of("providerContents", "providerCall", "pendingCalls", "input"));
        return out;
    }

    public static void expire(ObjectNode s, ObjectNode c) {
        ObjectNode a = find(s.path("actions"), str(c, "actionId")),
            r = find(s.path("runs"), str(c, "runId"));
        if (
            a != null &&
            str(a, "status").equals("PROPOSED") &&
            Instant.parse(str(a, "expiresAt")).isBefore(Instant.now())
        ) {
            a.put("status", "EXPIRED");
            if (r != null) {
                r.put("status", "STOPPED");
                event(r, "stop", "Confirmation expired", obj("code", "CONFIRMATION_EXPIRED"));
            }
        }
        if (
            r != null &&
            str(r, "status").equals("WAITING_FOR_INPUT") &&
            Instant.parse(str(r, "updatedAt")).plusSeconds(900).isBefore(Instant.now())
        ) {
            r.put("status", "STOPPED");
            c.remove("task");
            event(r, "stop", "Input wait expired", obj("code", "INPUT_EXPIRED"));
        }
    }

    public ObjectNode message(Sessions.Session session, String conv, String text, String clientId) {
        if (
            text.isBlank() || text.length() > 4000 || clientId.isBlank() || clientId.length() > 120
        ) {
            throw new Problem(
                422,
                "VALIDATION_FAILED",
                "Send a message of 1–4000 characters with a client message ID."
            );
        }
        ObjectNode accepted = store.change(session.workspace(), s -> {
            ObjectNode c = ownConversation(s, session, conv);
            expire(s, c);
            for (JsonNode n : s.path("runs")) {
                if (
                    str(n, "conversationId").equals(conv) &&
                    str(n, "clientMessageId").equals(clientId)
                ) {
                    return publicRun((ObjectNode) n);
                }
            }
            ObjectNode old = find(s.path("runs"), str(c, "runId"));
            if (old != null && Set.of("QUEUED", "RUNNING").contains(str(old, "status"))) {
                throw new Problem(
                    409,
                    "RUN_ACTIVE",
                    "Wait for the active run, or stop the assistant first."
                );
            }
            ObjectNode a = find(s.path("actions"), str(c, "actionId"));
            boolean yes = text.trim().matches("(?i)(yes|confirm|ok|okay|do it)[.!]?");
            if (a != null && str(a, "status").equals("PROPOSED") && !yes) {
                a.put("status", "SUPERSEDED");
                if (old != null) {
                    old.put("status", "STOPPED");
                }
            }
            if (old != null && str(old, "status").equals("WAITING_FOR_INPUT")) {
                old.put("status", "COMPLETED");
            }
            long recent = 0;
            for (JsonNode n : s.path("runs")) {
                if (Instant.parse(str(n, "createdAt")).isAfter(Instant.now().minusSeconds(60))) {
                    recent++;
                }
            }
            if (recent >= 30) {
                throw new Problem(
                    429,
                    "SESSION_RATE_LIMIT",
                    "Please wait before sending more messages."
                );
            }
            ObjectNode r = obj(
                "id",
                id("run"),
                "conversationId",
                conv,
                "clientMessageId",
                clientId,
                "input",
                text,
                "mode",
                str(c, "mode"),
                "status",
                "QUEUED",
                "packVersion",
                "1.0.0",
                "policyVersion",
                "1.0.0",
                "toolSchemaVersion",
                "1.0.0",
                "model",
                str(c, "mode").equals("live") ? gemini.model : "scripted-rehearsal-v1",
                "modelRequests",
                0,
                "toolCalls",
                0,
                "activeMs",
                0,
                "createdAt",
                Instant.now().toString(),
                "updatedAt",
                Instant.now().toString(),
                "events",
                arr(),
                "outputs",
                arr(),
                "usage",
                null
            );
            c.withArray("messages").add(
                obj("id", clientId, "role", "user", "text", text, "at", Instant.now().toString())
            );
            c.put("runId", str(r, "id"));
            s.withArray("runs").add(r);
            event(
                r,
                "understand",
                "Request received",
                obj("mode", str(c, "mode"), "packVersion", "1.0.0")
            );
            if (yes) {
                reply(
                    c,
                    r,
                    "Use the Confirm button on the exact action card. A typed reply cannot authorize a business change.",
                    a != null && str(a, "status").equals("PROPOSED")
                        ? "WAITING_FOR_CONFIRMATION"
                        : "COMPLETED"
                );
            }
            return publicRun(r);
        });
        if (str(accepted, "status").equals("QUEUED")) {
            workers.submit(() -> process(session, conv, str(accepted, "id")));
        }
        return accepted;
    }

    private void process(Sessions.Session session, String conv, String rid) {
        boolean acquired = false;
        long start = 0;
        try {
            boolean isLive = store.read(session.workspace(), s ->
                str(find(s.path("runs"), rid), "mode").equals("live")
            );
            if (isLive) {
                if (!live.tryAcquire(60, TimeUnit.SECONDS)) {
                    throw new Problem(
                        429,
                        "LIVE_QUEUE_TIMEOUT",
                        "Live AI is busy. Retry later or choose rehearsal."
                    );
                }
                acquired = true;
            }
            start = System.nanoTime();
            final long begin = start;
            boolean started = store.change(session.workspace(), s -> {
                ObjectNode r = find(s.path("runs"), rid);
                if (!str(r, "status").equals("QUEUED")) {
                    return false;
                }
                r.put("status", "RUNNING");
                r.put("startedAt", Instant.now().toString());
                r.put("deadlineAt", Instant.now().plusSeconds(120).toString());
                r.put(
                    "queueMs",
                    Duration.between(Instant.parse(str(r, "createdAt")), Instant.now()).toMillis()
                );
                return true;
            });
            if (!started) {
                return;
            }
            for (int step = 0; step < 16; step++) {
                ObjectNode snap = store.read(session.workspace(), s ->
                    obj(
                        "pack",
                        str(s, "pack"),
                        "conversation",
                        find(s.path("conversations"), conv).deepCopy(),
                        "run",
                        find(s.path("runs"), rid).deepCopy()
                    )
                );
                ObjectNode r = (ObjectNode) snap.get("run");
                if (!str(r, "status").equals("RUNNING")) {
                    return;
                }
                if ((System.nanoTime() - start) / 1000000 > 120000) {
                    throw new Problem(
                        408,
                        "RUN_TIME_LIMIT",
                        "The active processing deadline was reached. No unconfirmed change was executed."
                    );
                }
                ScenarioPack pack = packs.get(str(snap, "pack"));
                Decision d;
                if (isLive) {
                    if (
                        r.withArray("pendingCalls").isEmpty() &&
                        r.path("modelRequests").asInt() >= rounds
                    ) {
                        throw new Problem(
                            429,
                            "MODEL_ROUND_LIMIT",
                            "The model-round limit was reached. Please narrow your request."
                        );
                    }
                    boolean modelRequest = r.withArray("pendingCalls").isEmpty();
                    store.change(session.workspace(), s -> {
                        ObjectNode current = find(s.path("runs"), rid);
                        if (modelRequest) {
                            current.put("modelRequests", current.path("modelRequests").asInt() + 1);
                            event(
                                current,
                                "model",
                                "Gemini selecting the next permitted step",
                                obj("model", gemini.model)
                            );
                        }
                        return null;
                    });
                    try {
                        d = gemini.next(pack, (ObjectNode) snap.get("conversation"), r);
                    } catch (Problem problem) {
                        store.change(session.workspace(), s -> {
                            if (r.has("providerError")) {
                                find(s.path("runs"), rid).set(
                                    "providerError",
                                    r.get("providerError")
                                );
                            }
                            if (r.has("providerCauseType")) {
                                find(s.path("runs"), rid).set(
                                    "providerCauseType",
                                    r.get("providerCauseType")
                                );
                            }
                            return null;
                        });
                        throw problem;
                    }
                    store.change(session.workspace(), s -> {
                        ObjectNode current = find(s.path("runs"), rid);
                        for (String k : List.of(
                            "providerContents",
                            "providerCall",
                            "pendingCalls",
                            "usage",
                            "providerUsage",
                            "providerError"
                        )) {
                            if (r.has(k)) {
                                current.set(k, r.get(k));
                            }
                        }
                        return null;
                    });
                } else {
                    d = store.change(session.workspace(), s ->
                        pack.rehearse(
                            find(s.path("conversations"), conv),
                            find(s.path("runs"), rid)
                        )
                    );
                }
                if (d.tool() == null) {
                    store.change(session.workspace(), s -> {
                        ObjectNode current = find(s.path("runs"), rid);
                        if (str(current, "status").equals("RUNNING")) {
                            ObjectNode c = find(s.path("conversations"), conv);
                            current.put("responseOrigin", isLive ? "MODEL" : "SCRIPTED");
                            reply(
                                c,
                                current,
                                d.reply(),
                                d.needsInput() ? "WAITING_FOR_INPUT" : "COMPLETED"
                            );
                        }
                        return null;
                    });
                    return;
                }
                JsonNode result = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    final int index = attempt;
                    result = store.change(session.workspace(), s -> {
                        ObjectNode current = find(s.path("runs"), rid),
                            c = find(s.path("conversations"), conv);
                        if (!str(current, "status").equals("RUNNING")) {
                            return obj("stopped", true);
                        }
                        int count = current.path("toolCalls").asInt();
                        if (count >= 10) {
                            return obj(
                                "error",
                                obj("code", "TOOL_LIMIT"),
                                "message",
                                "Tool-call limit reached."
                            );
                        }
                        current.put("toolCalls", count + 1);
                        event(
                            current,
                            "tool",
                            index == 0 ? "Calling " + d.tool() : "Retrying read once",
                            obj("tool", d.tool(), "args", redact(d.args()), "attempt", index + 1)
                        );
                        try {
                            JsonNode schema = pack.schemas().get(d.tool());
                            if (schema == null) {
                                throw new Problem(
                                    422,
                                    "TOOL_NOT_ALLOWED",
                                    "This tool is not allowed for the active pack."
                                );
                            }
                            Schemas.validate(d.args(), schema);
                            JsonNode res = pack.invoke(
                                d.tool(),
                                d.args(),
                                new ToolContext(s, c, current, session.principal())
                            );
                            if (!res.isObject() || !res.path("message").isTextual()) {
                                throw new Problem(
                                    502,
                                    "TOOL_RESULT_INVALID",
                                    "The adapter returned an invalid result; no completion is confirmed."
                                );
                            }
                            event(
                                current,
                                "evidence",
                                "Tool result recorded",
                                obj("tool", d.tool(), "result", redact(res))
                            );
                            return res.deepCopy();
                        } catch (Problem e) {
                            event(
                                current,
                                e.code.equals("RESOURCE_NOT_AVAILABLE") ? "policy" : "error",
                                e.getMessage(),
                                obj("tool", d.tool(), "code", e.code, "retryable", e.status == 503)
                            );
                            return obj(
                                "error",
                                obj("code", e.code, "retryable", e.status == 503),
                                "message",
                                e.getMessage()
                            );
                        }
                    });
                    if (!result.path("error").path("retryable").asBoolean() || attempt == 1) {
                        break;
                    }
                }
                final JsonNode output = result;
                if (output.path("stopped").asBoolean()) {
                    return;
                }
                boolean paused = store.change(session.workspace(), s -> {
                    ObjectNode current = find(s.path("runs"), rid),
                        c = find(s.path("conversations"), conv);
                    current.withArray("outputs").add(obj("tool", d.tool(), "result", output));
                    if (isLive) {
                        gemini.result(current, d.tool(), output);
                    }
                    current.put("activeMs", (System.nanoTime() - begin) / 1000000);
                    current.put("updatedAt", Instant.now().toString());
                    current.put("responseOrigin", "BACKEND");
                    if (str(current, "status").equals("WAITING_FOR_CONFIRMATION")) {
                        reply(c, current, str(output, "message"), "WAITING_FOR_CONFIRMATION");
                        return true;
                    }
                    if (output.has("error")) {
                        if (
                            isLive &&
                            Set.of("VALIDATION_FAILED", "TOOL_NOT_ALLOWED").contains(
                                output.path("error").path("code").asText()
                            ) &&
                            current.path("argumentRepairs").asInt() == 0
                        ) {
                            current.put("argumentRepairs", 1);
                            event(
                                current,
                                "repair",
                                "One model argument repair permitted",
                                obj("code", output.path("error").path("code"))
                            );
                            return false;
                        }
                        reply(
                            c,
                            current,
                            str(output, "message"),
                            output
                                .path("error")
                                .path("code")
                                .asText()
                                .equals("RESOURCE_NOT_AVAILABLE")
                                ? "COMPLETED"
                                : "FAILED"
                        );
                        return true;
                    }
                    if (
                        output.has("policyCode") ||
                        output.has("action") ||
                        (output.has("sources") && output.path("sources").isEmpty())
                    ) {
                        reply(c, current, str(output, "message"), "COMPLETED");
                        return true;
                    }
                    if (!isLive && d.tool().equals("list_orders")) {
                        reply(c, current, str(output, "message"), "WAITING_FOR_INPUT");
                        return true;
                    }
                    return !str(current, "status").equals("RUNNING");
                });
                if (paused) {
                    return;
                }
            }
            throw new Problem(
                429,
                "MODEL_ROUND_LIMIT",
                "The model-round limit was reached. Please narrow your request."
            );
        } catch (Exception e) {
            Problem p =
                e instanceof Problem
                    ? (Problem) e
                    : new Problem(
                          500,
                          "RUN_FAILED",
                          "The run could not complete. Saved business state is preserved."
                      );
            store.change(session.workspace(), s -> {
                ObjectNode r = find(s.path("runs"), rid);
                if (r != null && Set.of("RUNNING", "QUEUED").contains(str(r, "status"))) {
                    event(r, "error", p.getMessage(), obj("code", p.code));
                    reply(find(s.path("conversations"), conv), r, p.getMessage(), "FAILED");
                }
                return null;
            });
        } finally {
            if (acquired) {
                live.release();
            }
        }
    }

    public static JsonNode redact(JsonNode n) {
        JsonNode copy = n.deepCopy();
        if (copy.isObject()) {
            ObjectNode o = (ObjectNode) copy;
            List<String> keys = new ArrayList<>();
            o.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if (
                    Set.of(
                        "line1",
                        "postalCode",
                        "contactEmail",
                        "email",
                        "summary",
                        "conversationSummary",
                        "contactPreference",
                        "message"
                    ).contains(k)
                ) {
                    o.put(k, "[redacted]");
                } else {
                    o.set(k, redact(o.get(k)));
                }
            }
        } else if (copy.isArray()) {
            ArrayNode a = (ArrayNode) copy;
            for (int i = 0; i < a.size(); i++) {
                a.set(i, redact(a.get(i)));
            }
        }
        return copy;
    }

    private static void reply(ObjectNode c, ObjectNode r, String text, String status) {
        ObjectNode message = obj(
            "id",
            id("msg"),
            "role",
            "assistant",
            "text",
            text,
            "at",
            Instant.now().toString(),
            "runId",
            str(r, "id"),
            "responseOrigin",
            r.path("responseOrigin").asText("BACKEND")
        );
        ArrayNode sources = arr();
        Set<String> seen = new HashSet<>();
        for (JsonNode output : r.withArray("outputs")) {
            for (JsonNode source : output.path("result").path("sources")) {
                if (seen.add(str(source, "id"))) {
                    sources.add(source.deepCopy());
                }
            }
        }
        if (!sources.isEmpty()) {
            message.set("sources", sources);
        }
        c.withArray("messages").add(message);
        r.put("status", status);
        r.put("updatedAt", Instant.now().toString());
        if (r.has("startedAt")) {
            r.put(
                "activeMs",
                Duration.between(Instant.parse(str(r, "startedAt")), Instant.now()).toMillis()
            );
        }
        event(
            r,
            "finish",
            status.equals("WAITING_FOR_INPUT")
                ? "Waiting for customer information"
                : status.equals("WAITING_FOR_CONFIRMATION")
                  ? "Waiting for exact confirmation"
                  : "Run " + status.toLowerCase(Locale.ROOT),
            obj("status", status)
        );
    }

    public ObjectNode run(Sessions.Session session, String id) {
        return store.read(session.workspace(), s -> {
            ObjectNode r = find(s.path("runs"), id);
            if (r == null) {
                throw Problem.missing();
            }
            ownConversation(s, session, str(r, "conversationId"));
            return publicRun(r);
        });
    }

    public ObjectNode stop(Sessions.Session session, String id) {
        return store.change(session.workspace(), s -> {
            ObjectNode r = find(s.path("runs"), id);
            if (r == null) {
                throw Problem.missing();
            }
            ObjectNode c = ownConversation(s, session, str(r, "conversationId"));
            if (
                Set.of(
                    "QUEUED",
                    "RUNNING",
                    "WAITING_FOR_INPUT",
                    "WAITING_FOR_CONFIRMATION"
                ).contains(str(r, "status"))
            ) {
                r.put("status", "STOPPED");
                ObjectNode a = find(s.path("actions"), str(c, "actionId"));
                if (a != null && str(a, "status").equals("PROPOSED")) {
                    a.put("status", "SUPERSEDED");
                }
                event(r, "stop", "Assistant stopped; dispatched operations are not undone", obj());
            }
            return publicRun(r);
        });
    }
}

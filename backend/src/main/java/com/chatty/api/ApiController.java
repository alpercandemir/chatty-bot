package com.chatty.api;

import static com.chatty.store.Json.*;
import static com.chatty.store.WorkspaceStore.event;

import com.chatty.harness.*;
import com.chatty.model.GeminiAdapter;
import com.chatty.scenario.*;
import com.chatty.store.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ApiController {

    private final Sessions sessions;
    private final WorkspaceStore store;
    private final Harness harness;
    private final Actions actions;
    private final CommercePack commerce;
    private final Knowledge knowledge;
    private final GeminiAdapter gemini;
    private final String defaultMode;
    private final List<ScenarioPack> packs;
    private final ExecutorService stream = Executors.newVirtualThreadPerTaskExecutor();

    public ApiController(
        Sessions s,
        WorkspaceStore st,
        Harness h,
        Actions a,
        CommercePack c,
        Knowledge k,
        GeminiAdapter g,
        @Value("${chatty.mode}") String mode,
        List<ScenarioPack> packs
    ) {
        sessions = s;
        store = st;
        harness = h;
        actions = a;
        commerce = c;
        knowledge = k;
        gemini = g;
        defaultMode = mode.equals("auto") ? (g.configured() ? "live" : "scripted") : mode;
        this.packs = packs;
    }

    @ExceptionHandler(Problem.class)
    public ResponseEntity<JsonNode> problem(Problem p) {
        return ResponseEntity.status(p.status).body(
            obj(
                "error",
                obj(
                    "code",
                    p.code,
                    "message",
                    p.getMessage(),
                    "retryable",
                    p.status == 503 || p.status == 429
                ),
                "meta",
                obj("simulated", true)
            )
        );
    }

    @GetMapping("/api/v1/demo/packs")
    public JsonNode packs(HttpServletRequest req) {
        sessions.presenter(req);
        ArrayNode manifests = arr();
        packs.forEach(p -> manifests.add(p.manifest()));
        return manifests;
    }

    @GetMapping("/api/v1/health")
    public JsonNode health() {
        return obj("status", "UP", "simulated", true);
    }

    @PostMapping("/api/v1/demo/presenter")
    public JsonNode presenter(HttpServletRequest req, HttpServletResponse res) {
        var p = sessions.bootstrap(req, res);
        return obj(
            "presenterId",
            p.presenter(),
            "model",
            gemini.model,
            "keyConfigured",
            gemini.configured(),
            "defaultMode",
            defaultMode,
            "principals",
            store.seed.path("principals"),
            "workspaces",
            store.seed.path("workspaces")
        );
    }

    @PostMapping("/api/v1/demo/sessions")
    public JsonNode select(
        @RequestBody JsonNode body,
        HttpServletRequest req,
        HttpServletResponse res
    ) {
        return sessions.select(
            sessions.presenter(req),
            body.path("pack").asText("commerce"),
            str(body, "principalId"),
            res
        );
    }

    @GetMapping("/api/v1/demo/scenarios")
    public JsonNode scenarios(HttpServletRequest req) {
        sessions.presenter(req);
        return store.scenarios;
    }

    @PostMapping("/api/v1/conversations")
    public JsonNode create(@RequestBody JsonNode body, HttpServletRequest req) {
        return harness.create(sessions.customer(req), body.path("mode").asText(defaultMode));
    }

    @GetMapping("/api/v1/conversations/{id}")
    public JsonNode conversation(@PathVariable String id, HttpServletRequest req) {
        return harness.snapshot(sessions.customer(req), id);
    }

    @PostMapping("/api/v1/conversations/{id}/messages")
    public ResponseEntity<JsonNode> message(
        @PathVariable String id,
        @RequestBody JsonNode body,
        HttpServletRequest req
    ) {
        ObjectNode run = harness.message(
            sessions.customer(req),
            id,
            str(body, "text"),
            str(body, "clientMessageId")
        );
        return ResponseEntity.accepted().body(
            obj(
                "conversationId",
                id,
                "runId",
                str(run, "id"),
                "status",
                str(run, "status"),
                "eventsUrl",
                "/api/v1/runs/" + str(run, "id") + "/events"
            )
        );
    }

    @GetMapping("/api/v1/runs/{id}")
    public JsonNode run(@PathVariable String id, HttpServletRequest req) {
        return harness.run(sessions.customer(req), id);
    }

    @PostMapping("/api/v1/runs/{id}/stop")
    public JsonNode stop(@PathVariable String id, HttpServletRequest req) {
        return harness.stop(sessions.customer(req), id);
    }

    @GetMapping(value = "/api/v1/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
        @PathVariable String id,
        @RequestHeader(value = "Last-Event-ID", required = false) String last,
        @RequestParam(defaultValue = "0") int after,
        HttpServletRequest req
    ) {
        var session = sessions.customer(req);
        harness.run(session, id);
        SseEmitter out = new SseEmitter(65000L);
        final int initial = last == null ? after : Integer.parseInt(last);
        stream.submit(() -> {
            int cursor = initial;
            try {
                for (int ticks = 0; ticks < 120; ticks++) {
                    ObjectNode r = harness.run(session, id);
                    for (JsonNode e : r.path("events")) {
                        if (e.path("id").asInt() > cursor) {
                            cursor = e.path("id").asInt();
                            out.send(
                                SseEmitter.event()
                                    .id(String.valueOf(cursor))
                                    .name("run-event")
                                    .data(e)
                            );
                        }
                    }
                    if (!Set.of("QUEUED", "RUNNING").contains(str(r, "status"))) {
                        out.send(
                            SseEmitter.event()
                                .name("snapshot")
                                .data(harness.snapshot(session, str(r, "conversationId")))
                        );
                        out.complete();
                        return;
                    }
                    Thread.sleep(500);
                }
                out.complete();
            } catch (Exception e) {
                out.complete();
            }
        });
        return out;
    }

    @GetMapping("/api/v1/actions/{id}")
    public JsonNode action(@PathVariable String id, HttpServletRequest req) {
        return actions.get(sessions.customer(req), id);
    }

    @PostMapping("/api/v1/actions/{id}/confirm")
    public JsonNode confirm(@PathVariable String id, HttpServletRequest req) {
        return actions.decide(sessions.customer(req), id, true);
    }

    @PostMapping("/api/v1/actions/{id}/decline")
    public JsonNode decline(@PathVariable String id, HttpServletRequest req) {
        return actions.decide(sessions.customer(req), id, false);
    }

    @GetMapping("/api/v1/demo/state")
    public JsonNode state(
        @RequestParam(defaultValue = "commerce") String pack,
        HttpServletRequest req
    ) {
        String w = sessions.workspace(sessions.presenter(req), pack);
        return store.read(w, s -> {
            ObjectNode o = s.deepCopy();
            for (JsonNode c : o.path("conversations")) {
                ((ObjectNode) c).remove("sessionToken");
            }
            ArrayNode runs = arr();
            for (JsonNode r : o.path("runs")) {
                runs.add(Harness.publicRun((ObjectNode) r));
            }
            o.set("runs", runs);
            return o;
        });
    }

    @PostMapping("/api/v1/demo/reset")
    public JsonNode reset(@RequestBody JsonNode body, HttpServletRequest req) {
        String pack = body.path("pack").asText("commerce"),
            w = sessions.workspace(sessions.presenter(req), pack);
        return store.change(w, s -> {
            for (JsonNode a : s.path("actions")) {
                if (Set.of("UNKNOWN", "EXECUTING").contains(str(a, "status"))) {
                    throw new Problem(
                        409,
                        "RESET_BLOCKED",
                        "Reconcile the pending operation before reset."
                    );
                }
            }
            for (JsonNode r : s.path("runs")) {
                if (Set.of("QUEUED", "RUNNING").contains(str(r, "status"))) {
                    throw new Problem(409, "RESET_BLOCKED", "Stop the active run before reset.");
                }
            }
            ObjectNode fresh = store.fresh(pack);
            fresh.put("workspaceId", w);
            store.syncKnowledge(w, fresh);
            s.removeAll();
            s.setAll(fresh);
            return obj("resetId", str(s, "generation"), "resetAt", str(s, "resetAt"));
        });
    }

    @PostMapping("/api/v1/demo/purge")
    public JsonNode purge(@RequestBody JsonNode body, HttpServletRequest req) {
        String w = sessions.workspace(
            sessions.presenter(req),
            body.path("pack").asText("commerce")
        );
        store.purge(w);
        return obj("purged", true, "scope", "selected presenter workspace");
    }

    @PostMapping("/api/v1/demo/faults")
    public JsonNode fault(@RequestBody JsonNode body, HttpServletRequest req) {
        String w = sessions.workspace(
            sessions.presenter(req),
            body.path("pack").asText("commerce")
        );
        if (
            !Set.of(
                "READ_TIMEOUT",
                "WRITE_FAILURE_BEFORE_COMMIT",
                "WRITE_TIMEOUT_AFTER_COMMIT"
            ).contains(str(body, "fault"))
        ) {
            throw new Problem(422, "VALIDATION_FAILED", "Unknown fault.");
        }
        int count = body.path("occurrences").asInt(1);
        if (count < 1 || count > 10) {
            throw new Problem(
                422,
                "VALIDATION_FAILED",
                "Fault occurrences must be between one and ten."
            );
        }
        return store.change(w, s -> {
            ObjectNode f = obj(
                "id",
                id("fault"),
                "fault",
                str(body, "fault"),
                "orderId",
                str(body, "orderId"),
                "occurrences",
                count
            );
            s.withArray("faults").add(f);
            return f;
        });
    }

    @PostMapping("/api/v1/demo/orders/{id}/advance")
    public JsonNode advance(@PathVariable String id, HttpServletRequest req) {
        String w = sessions.workspace(sessions.presenter(req), "commerce");
        return store.change(w, s -> {
            ObjectNode o = find(s.path("orders"), id);
            if (o == null) {
                throw Problem.missing();
            }
            for (JsonNode a : s.path("actions")) {
                if (
                    str(a.path("args"), "orderId").equals(id) && str(a, "status").equals("UNKNOWN")
                ) {
                    throw new Problem(409, "RESOURCE_OPERATION_PENDING", "Reconcile first.");
                }
            }
            o.put("status", "SHIPPED");
            o.put("version", o.path("version").asInt() + 1);
            return o.deepCopy();
        });
    }

    @PostMapping("/api/v1/demo/operations/{id}/reconcile")
    public JsonNode reconcile(
        @PathVariable String id,
        @RequestParam(defaultValue = "commerce") String pack,
        HttpServletRequest req
    ) {
        return actions.reconcile(sessions.workspace(sessions.presenter(req), pack), id);
    }

    private Sessions.Session internal(HttpServletRequest req) {
        var p = sessions.presenter(req);
        var c = sessions.customer(req);
        if (!p.presenter().equals(c.presenter())) {
            throw Problem.missing();
        }
        return c;
    }

    @GetMapping("/internal/demo/v1/orders")
    public JsonNode orders(HttpServletRequest req) {
        var session = internal(req);
        return store.read(session.workspace(), s -> {
            ArrayNode a = arr();
            for (JsonNode n : s.path("orders")) {
                if (str(n, "principalId").equals(session.principal())) {
                    a.add(n);
                }
            }
            return envelope(a);
        });
    }

    @GetMapping("/internal/demo/v1/orders/{id}")
    public JsonNode order(@PathVariable String id, HttpServletRequest req) {
        var session = internal(req);
        return store.read(session.workspace(), s -> envelope(context(s, session).ownOrder(id)));
    }

    @GetMapping("/internal/demo/v1/orders/{id}/shipments")
    public JsonNode shipments(@PathVariable String id, HttpServletRequest req) {
        var session = internal(req);
        return store.read(session.workspace(), s -> {
            context(s, session).ownOrder(id);
            ArrayNode a = arr();
            for (JsonNode sh : s.path("shipments")) {
                if (str(sh, "orderId").equals(id)) {
                    a.add(sh);
                }
            }
            return envelope(obj("orderId", id, "shipments", a));
        });
    }

    @PostMapping("/internal/demo/v1/orders/{id}/{kind}-eligibility")
    public JsonNode eligibility(
        @PathVariable String id,
        @PathVariable String kind,
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest req
    ) {
        var session = internal(req);
        return store.read(session.workspace(), s -> {
            ToolContext x = context(s, session);
            ObjectNode o = x.ownOrder(id);
            String type = kind.equals("cancellation")
                ? "prepare_cancellation"
                : "prepare_address_change";
            JsonNode args =
                body == null
                    ? obj("orderId", id)
                    : obj("orderId", id, "address", body.path("address"));
            JsonNode result = commerce.eligibility(o, type, args, x);
            return envelope(
                result == null
                    ? obj(
                          "allowed",
                          true,
                          "policyCode",
                          "ALLOWED",
                          "version",
                          o.path("version").asInt()
                      )
                    : result
            );
        });
    }

    @GetMapping("/internal/demo/v1/knowledge/search")
    public JsonNode knowledge(@RequestParam String query, HttpServletRequest req) {
        var session = internal(req);
        return store.read(session.workspace(), s -> envelope(knowledge.search(query, s)));
    }

    @GetMapping("/internal/demo/v1/operations/{id}")
    public JsonNode operation(@PathVariable String id, HttpServletRequest req) {
        var session = internal(req);
        return store.read(session.workspace(), s -> {
            ObjectNode op = find(s.path("operations"), id);
            if (op == null) {
                throw Problem.missing();
            }
            ObjectNode a = Actions.own(s, session, str(op, "actionId"));
            return envelope(
                obj(
                    "operationId",
                    id,
                    "status",
                    str(a, "status"),
                    "result",
                    str(a, "status").equals("UNKNOWN") ? null : op.path("result")
                )
            );
        });
    }

    @PostMapping("/internal/demo/v1/orders/{id}/cancel")
    public JsonNode cancel(
        @PathVariable String id,
        @RequestBody JsonNode body,
        HttpServletRequest req
    ) {
        return recordedMutation(req, body, "prepare_cancellation", id);
    }

    @PatchMapping("/internal/demo/v1/orders/{id}/delivery-address")
    public JsonNode address(
        @PathVariable String id,
        @RequestBody JsonNode body,
        HttpServletRequest req
    ) {
        return recordedMutation(req, body, "prepare_address_change", id);
    }

    @PostMapping("/internal/demo/v1/support-tickets")
    public JsonNode ticket(@RequestBody JsonNode body, HttpServletRequest req) {
        return recordedMutation(req, body, "prepare_support_request", null);
    }

    @PostMapping("/internal/demo/v1/callback-requests")
    public JsonNode callback(@RequestBody JsonNode body, HttpServletRequest req) {
        return recordedMutation(req, body, "prepare_callback_request", null);
    }

    private JsonNode recordedMutation(
        HttpServletRequest req,
        JsonNode body,
        String type,
        String resource
    ) {
        var session = internal(req);
        ObjectNode a = actions.get(session, str(body, "actionId"));
        if (
            !str(a, "operationId").equals(str(body, "operationId")) ||
            !str(a, "type").equals(type) ||
            (resource != null && !str(a.path("args"), "orderId").equals(resource))
        ) {
            throw new Problem(
                409,
                "OPERATION_CONFLICT",
                "Operation binding does not match the recorded proposal."
            );
        }
        if (resource != null) {
            String match = req.getHeader("If-Match"),
                key = req.getHeader("Idempotency-Key");
            if (
                body.path("expectedVersion").asInt(-1) != a.path("expectedVersion").asInt() ||
                match == null ||
                !match.replace("\"", "").equals(a.path("expectedVersion").asText()) ||
                !str(a, "operationId").equals(key)
            ) {
                throw new Problem(
                    409,
                    "OPERATION_CONFLICT",
                    "Version and idempotency headers must match the saved action."
                );
            }
            if (
                type.equals("prepare_address_change") &&
                !body.path("address").equals(a.path("args").path("address"))
            ) {
                throw new Problem(
                    409,
                    "OPERATION_CONFLICT",
                    "Address differs from the confirmed proposal."
                );
            }
        }
        if (!str(a, "status").equals("SUCCEEDED")) {
            throw new Problem(
                409,
                "ACTION_NOT_CONFIRMABLE",
                "Only customer confirmation may dispatch this operation. Inspect the existing action status."
            );
        }
        return envelope(a.path("result"));
    }

    private ToolContext context(ObjectNode s, Sessions.Session session) {
        return new ToolContext(
            s,
            obj("id", "internal"),
            obj("id", "internal", "events", arr()),
            session.principal()
        );
    }

    private JsonNode envelope(JsonNode data) {
        return obj(
            "data",
            data,
            "meta",
            obj(
                "simulated",
                true,
                "fixtureVersion",
                "1.0.0",
                "asOf",
                java.time.Instant.now().toString()
            )
        );
    }
}

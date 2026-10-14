package com.chatty.api;

import static com.chatty.store.Json.arr;
import static com.chatty.store.Json.find;
import static com.chatty.store.Json.id;
import static com.chatty.store.Json.obj;
import static com.chatty.store.Json.str;

import com.chatty.harness.Actions;
import com.chatty.harness.Harness;
import com.chatty.model.GeminiAdapter;
import com.chatty.scenario.CommercePack;
import com.chatty.scenario.Knowledge;
import com.chatty.scenario.ScenarioPack;
import com.chatty.scenario.ToolContext;
import com.chatty.store.WorkspaceStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
            Sessions sessions,
            WorkspaceStore store,
            Harness harness,
            Actions actions,
            CommercePack commerce,
            Knowledge knowledge,
            GeminiAdapter gemini,
            @Value("${chatty.mode}") String mode,
            List<ScenarioPack> packs) {
        this.sessions = sessions;
        this.store = store;
        this.harness = harness;
        this.actions = actions;
        this.commerce = commerce;
        this.knowledge = knowledge;
        this.gemini = gemini;
        defaultMode = mode.equals("auto") ? (gemini.configured() ? "live" : "scripted") : mode;
        this.packs = packs;
    }

    @ExceptionHandler(Problem.class)
    public ResponseEntity<JsonNode> problem(Problem problem) {
        return ResponseEntity.status(problem.status)
                .body(
                        obj(
                                "error",
                                obj(
                                        "code",
                                        problem.code,
                                        "message",
                                        problem.getMessage(),
                                        "retryable",
                                        problem.status == 503 || problem.status == 429),
                                "meta",
                                obj("simulated", true)));
    }

    @GetMapping("/api/v1/demo/packs")
    public JsonNode packs(HttpServletRequest request) {
        sessions.presenter(request);
        ArrayNode manifests = arr();
        packs.forEach(pack -> manifests.add(pack.manifest()));
        return manifests;
    }

    @GetMapping("/api/v1/health")
    public JsonNode health() {
        return obj("status", "UP", "simulated", true);
    }

    @PostMapping("/api/v1/demo/presenter")
    public JsonNode presenter(HttpServletRequest request, HttpServletResponse response) {
        var presenterSession = sessions.bootstrap(request, response);
        return obj(
                "presenterId",
                presenterSession.presenter(),
                "model",
                gemini.model,
                "keyConfigured",
                gemini.configured(),
                "defaultMode",
                defaultMode,
                "principals",
                store.seed.path("principals"),
                "workspaces",
                store.seed.path("workspaces"));
    }

    @PostMapping("/api/v1/demo/sessions")
    public JsonNode select(
            @RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response) {
        return sessions.select(
                sessions.presenter(request),
                body.path("pack").asText("commerce"),
                str(body, "principalId"),
                response);
    }

    @GetMapping("/api/v1/demo/scenarios")
    public JsonNode scenarios(HttpServletRequest request) {
        sessions.presenter(request);
        return store.scenarios;
    }

    @PostMapping("/api/v1/conversations")
    public JsonNode create(@RequestBody JsonNode body, HttpServletRequest request) {
        return harness.create(sessions.customer(request), body.path("mode").asText(defaultMode));
    }

    @GetMapping("/api/v1/conversations/{id}")
    public JsonNode conversation(@PathVariable String id, HttpServletRequest request) {
        return harness.snapshot(sessions.customer(request), id);
    }

    @PostMapping("/api/v1/conversations/{id}/messages")
    public ResponseEntity<JsonNode> message(
            @PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        ObjectNode run =
                harness.message(
                        sessions.customer(request),
                        id,
                        str(body, "text"),
                        str(body, "clientMessageId"));
        return ResponseEntity.accepted()
                .body(
                        obj(
                                "conversationId",
                                id,
                                "runId",
                                str(run, "id"),
                                "status",
                                str(run, "status"),
                                "eventsUrl",
                                "/api/v1/runs/" + str(run, "id") + "/events"));
    }

    @GetMapping("/api/v1/runs/{id}")
    public JsonNode run(@PathVariable String id, HttpServletRequest request) {
        return harness.run(sessions.customer(request), id);
    }

    @PostMapping("/api/v1/runs/{id}/stop")
    public JsonNode stop(@PathVariable String id, HttpServletRequest request) {
        return harness.stop(sessions.customer(request), id);
    }

    @GetMapping(value = "/api/v1/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(defaultValue = "0") int after,
            HttpServletRequest request) {
        var session = sessions.customer(request);
        harness.run(session, id);
        SseEmitter emitter = new SseEmitter(65000L);
        final int initial = lastEventId == null ? after : Integer.parseInt(lastEventId);
        stream.submit(() -> streamRunEvents(session, id, emitter, initial));
        return emitter;
    }

    private void streamRunEvents(
            Sessions.Session session, String id, SseEmitter emitter, int initial) {
        int cursor = initial;
        try {
            for (int ticks = 0; ticks < 120; ticks++) {
                ObjectNode run = harness.run(session, id);
                for (JsonNode runEvent : run.path("events")) {
                    if (runEvent.path("id").asInt() > cursor) {
                        cursor = runEvent.path("id").asInt();
                        emitter.send(
                                SseEmitter.event()
                                        .id(String.valueOf(cursor))
                                        .name("run-event")
                                        .data(runEvent));
                    }
                }
                if (!Set.of("QUEUED", "RUNNING").contains(str(run, "status"))) {
                    emitter.send(
                            SseEmitter.event()
                                    .name("snapshot")
                                    .data(harness.snapshot(session, str(run, "conversationId"))));
                    emitter.complete();
                    return;
                }
                Thread.sleep(500);
            }
            emitter.complete();
        } catch (Exception exception) {
            emitter.complete();
        }
    }

    @GetMapping("/api/v1/actions/{id}")
    public JsonNode action(@PathVariable String id, HttpServletRequest request) {
        return actions.get(sessions.customer(request), id);
    }

    @PostMapping("/api/v1/actions/{id}/confirm")
    public JsonNode confirm(@PathVariable String id, HttpServletRequest request) {
        return actions.decide(sessions.customer(request), id, true);
    }

    @PostMapping("/api/v1/actions/{id}/decline")
    public JsonNode decline(@PathVariable String id, HttpServletRequest request) {
        return actions.decide(sessions.customer(request), id, false);
    }

    @GetMapping("/api/v1/demo/state")
    public JsonNode state(
            @RequestParam(defaultValue = "commerce") String pack, HttpServletRequest request) {
        String workspaceId = sessions.workspace(sessions.presenter(request), pack);
        return store.read(
                workspaceId,
                state -> {
                    ObjectNode publicState = state.deepCopy();
                    for (JsonNode conversation : publicState.path("conversations")) {
                        ((ObjectNode) conversation).remove("sessionToken");
                    }
                    ArrayNode runs = arr();
                    for (JsonNode run : publicState.path("runs")) {
                        runs.add(Harness.publicRun((ObjectNode) run));
                    }
                    publicState.set("runs", runs);
                    return publicState;
                });
    }

    @PostMapping("/api/v1/demo/reset")
    public JsonNode reset(@RequestBody JsonNode body, HttpServletRequest request) {
        String pack = body.path("pack").asText("commerce");
        String workspaceId = sessions.workspace(sessions.presenter(request), pack);
        return store.change(
                workspaceId,
                state -> {
                    for (JsonNode action : state.path("actions")) {
                        if (Set.of("UNKNOWN", "EXECUTING").contains(str(action, "status"))) {
                            throw new Problem(
                                    409,
                                    "RESET_BLOCKED",
                                    "Reconcile the pending operation before reset.");
                        }
                    }
                    for (JsonNode run : state.path("runs")) {
                        if (Set.of("QUEUED", "RUNNING").contains(str(run, "status"))) {
                            throw new Problem(
                                    409, "RESET_BLOCKED", "Stop the active run before reset.");
                        }
                    }
                    ObjectNode fresh = store.fresh(pack);
                    fresh.put("workspaceId", workspaceId);
                    store.syncKnowledge(workspaceId, fresh);
                    state.removeAll();
                    state.setAll(fresh);
                    return obj(
                            "resetId", str(state, "generation"), "resetAt", str(state, "resetAt"));
                });
    }

    @PostMapping("/api/v1/demo/purge")
    public JsonNode purge(@RequestBody JsonNode body, HttpServletRequest request) {
        String workspaceId =
                sessions.workspace(
                        sessions.presenter(request), body.path("pack").asText("commerce"));
        store.purge(workspaceId);
        return obj("purged", true, "scope", "selected presenter workspace");
    }

    @PostMapping("/api/v1/demo/faults")
    public JsonNode fault(@RequestBody JsonNode body, HttpServletRequest request) {
        String workspaceId =
                sessions.workspace(
                        sessions.presenter(request), body.path("pack").asText("commerce"));
        if (!Set.of("READ_TIMEOUT", "WRITE_FAILURE_BEFORE_COMMIT", "WRITE_TIMEOUT_AFTER_COMMIT")
                .contains(str(body, "fault"))) {
            throw new Problem(422, "VALIDATION_FAILED", "Unknown fault.");
        }
        int count = body.path("occurrences").asInt(1);
        if (count < 1 || count > 10) {
            throw new Problem(
                    422, "VALIDATION_FAILED", "Fault occurrences must be between one and ten.");
        }
        return store.change(
                workspaceId,
                state -> {
                    ObjectNode fault =
                            obj(
                                    "id",
                                    id("fault"),
                                    "fault",
                                    str(body, "fault"),
                                    "orderId",
                                    str(body, "orderId"),
                                    "occurrences",
                                    count);
                    state.withArray("faults").add(fault);
                    return fault;
                });
    }

    @PostMapping("/api/v1/demo/orders/{id}/advance")
    public JsonNode advance(@PathVariable String id, HttpServletRequest request) {
        String workspaceId = sessions.workspace(sessions.presenter(request), "commerce");
        return store.change(
                workspaceId,
                state -> {
                    ObjectNode order = find(state.path("orders"), id);
                    if (order == null) {
                        throw Problem.missing();
                    }
                    for (JsonNode action : state.path("actions")) {
                        if (str(action.path("args"), "orderId").equals(id)
                                && str(action, "status").equals("UNKNOWN")) {
                            throw new Problem(
                                    409, "RESOURCE_OPERATION_PENDING", "Reconcile first.");
                        }
                    }
                    order.put("status", "SHIPPED");
                    order.put("version", order.path("version").asInt() + 1);
                    return order.deepCopy();
                });
    }

    @PostMapping("/api/v1/demo/operations/{id}/reconcile")
    public JsonNode reconcile(
            @PathVariable String id,
            @RequestParam(defaultValue = "commerce") String pack,
            HttpServletRequest request) {
        return actions.reconcile(sessions.workspace(sessions.presenter(request), pack), id);
    }

    private Sessions.Session internal(HttpServletRequest request) {
        var presenterSession = sessions.presenter(request);
        var customerSession = sessions.customer(request);
        if (!presenterSession.presenter().equals(customerSession.presenter())) {
            throw Problem.missing();
        }
        return customerSession;
    }

    @GetMapping("/internal/demo/v1/orders")
    public JsonNode orders(HttpServletRequest request) {
        var session = internal(request);
        return store.read(
                session.workspace(),
                state -> {
                    ArrayNode records = arr();
                    for (JsonNode n : state.path("orders")) {
                        if (str(n, "principalId").equals(session.principal())) {
                            records.add(n);
                        }
                    }
                    return envelope(records);
                });
    }

    @GetMapping("/internal/demo/v1/orders/{id}")
    public JsonNode order(@PathVariable String id, HttpServletRequest request) {
        var session = internal(request);
        return store.read(
                session.workspace(), state -> envelope(context(state, session).ownOrder(id)));
    }

    @GetMapping("/internal/demo/v1/orders/{id}/shipments")
    public JsonNode shipments(@PathVariable String id, HttpServletRequest request) {
        var session = internal(request);
        return store.read(
                session.workspace(),
                state -> {
                    context(state, session).ownOrder(id);
                    ArrayNode records = arr();
                    for (JsonNode shipment : state.path("shipments")) {
                        if (str(shipment, "orderId").equals(id)) {
                            records.add(shipment);
                        }
                    }
                    return envelope(obj("orderId", id, "shipments", records));
                });
    }

    @PostMapping("/internal/demo/v1/orders/{id}/{kind}-eligibility")
    public JsonNode eligibility(
            @PathVariable String id,
            @PathVariable String kind,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        var session = internal(request);
        return store.read(
                session.workspace(),
                state -> {
                    ToolContext context = context(state, session);
                    ObjectNode order = context.ownOrder(id);
                    String type =
                            kind.equals("cancellation")
                                    ? "prepare_cancellation"
                                    : "prepare_address_change";
                    JsonNode args =
                            body == null
                                    ? obj("orderId", id)
                                    : obj("orderId", id, "address", body.path("address"));
                    JsonNode result = commerce.eligibility(order, type, args, context);
                    return envelope(
                            result == null
                                    ? obj(
                                            "allowed",
                                            true,
                                            "policyCode",
                                            "ALLOWED",
                                            "version",
                                            order.path("version").asInt())
                                    : result);
                });
    }

    @GetMapping("/internal/demo/v1/knowledge/search")
    public JsonNode knowledge(@RequestParam String query, HttpServletRequest request) {
        var session = internal(request);
        return store.read(session.workspace(), state -> envelope(knowledge.search(query, state)));
    }

    @GetMapping("/internal/demo/v1/operations/{id}")
    public JsonNode operation(@PathVariable String id, HttpServletRequest request) {
        var session = internal(request);
        return store.read(
                session.workspace(),
                state -> {
                    ObjectNode operation = find(state.path("operations"), id);
                    if (operation == null) {
                        throw Problem.missing();
                    }
                    ObjectNode action = Actions.own(state, session, str(operation, "actionId"));
                    return envelope(
                            obj(
                                    "operationId",
                                    id,
                                    "status",
                                    str(action, "status"),
                                    "result",
                                    str(action, "status").equals("UNKNOWN")
                                            ? null
                                            : operation.path("result")));
                });
    }

    @PostMapping("/internal/demo/v1/orders/{id}/cancel")
    public JsonNode cancel(
            @PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        return recordedMutation(request, body, "prepare_cancellation", id);
    }

    @PatchMapping("/internal/demo/v1/orders/{id}/delivery-address")
    public JsonNode address(
            @PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        return recordedMutation(request, body, "prepare_address_change", id);
    }

    @PostMapping("/internal/demo/v1/support-tickets")
    public JsonNode ticket(@RequestBody JsonNode body, HttpServletRequest request) {
        return recordedMutation(request, body, "prepare_support_request", null);
    }

    @PostMapping("/internal/demo/v1/callback-requests")
    public JsonNode callback(@RequestBody JsonNode body, HttpServletRequest request) {
        return recordedMutation(request, body, "prepare_callback_request", null);
    }

    private JsonNode recordedMutation(
            HttpServletRequest request, JsonNode body, String type, String resource) {
        var session = internal(request);
        ObjectNode action = actions.get(session, str(body, "actionId"));
        if (!str(action, "operationId").equals(str(body, "operationId"))
                || !str(action, "type").equals(type)
                || (resource != null && !str(action.path("args"), "orderId").equals(resource))) {
            throw new Problem(
                    409,
                    "OPERATION_CONFLICT",
                    "Operation binding does not match the recorded proposal.");
        }
        if (resource != null) {
            String match = request.getHeader("If-Match");
            String key = request.getHeader("Idempotency-Key");
            if (body.path("expectedVersion").asInt(-1) != action.path("expectedVersion").asInt()
                    || match == null
                    || !match.replace("\"", "").equals(action.path("expectedVersion").asText())
                    || !str(action, "operationId").equals(key)) {
                throw new Problem(
                        409,
                        "OPERATION_CONFLICT",
                        "Version and idempotency headers must match the saved action.");
            }
            if (type.equals("prepare_address_change")
                    && !body.path("address").equals(action.path("args").path("address"))) {
                throw new Problem(
                        409, "OPERATION_CONFLICT", "Address differs from the confirmed proposal.");
            }
        }
        if (!str(action, "status").equals("SUCCEEDED")) {
            throw new Problem(
                    409,
                    "ACTION_NOT_CONFIRMABLE",
                    "Only customer confirmation may dispatch this operation. Inspect the existing action status.");
        }
        return envelope(action.path("result"));
    }

    private ToolContext context(ObjectNode state, Sessions.Session session) {
        return new ToolContext(
                state,
                obj("id", "internal"),
                obj("id", "internal", "events", arr()),
                session.principal());
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
                        java.time.Instant.now().toString()));
    }
}

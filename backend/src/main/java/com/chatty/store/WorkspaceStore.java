package com.chatty.store;

import static com.chatty.store.Json.M;
import static com.chatty.store.Json.find;
import static com.chatty.store.Json.id;
import static com.chatty.store.Json.obj;
import static com.chatty.store.Json.parse;
import static com.chatty.store.Json.str;

import java.time.Instant;
import java.util.function.Function;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class WorkspaceStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public final JsonNode seed;
    public final JsonNode scenarios;

    public WorkspaceStore(JdbcTemplate jdbc, TransactionTemplate tx) throws Exception {
        this.jdbc = jdbc;
        this.tx = tx;
        seed = M.readTree(new ClassPathResource("demo/fixtures/seed.json").getInputStream());
        scenarios = M.readTree(new ClassPathResource("demo/scenarios.json").getInputStream());
    }

    public ObjectNode fresh(String pack) {
        String base = pack.equals("commerce") ? "demo-northstar" : "demo-clearspace";
        ObjectNode state =
                obj(
                        "pack",
                        pack,
                        "generation",
                        id("reset"),
                        "resetAt",
                        Instant.now().toString(),
                        "fixtureVersion",
                        "1.0.0");
        copyWorkspaceFixtures(state, base);
        initializeOrders(state);
        initializeShipments(state);
        for (String collectionName :
                new String[] {
                    "conversations",
                    "runs",
                    "actions",
                    "operations",
                    "supportTickets",
                    "callbackRequests",
                    "faults",
                }) {
            state.putArray(collectionName);
        }
        return state;
    }

    private void copyWorkspaceFixtures(ObjectNode state, String fixtureWorkspaceId) {
        for (String collectionName :
                new String[] {
                    "orders", "shipments", "knowledgeArticles", "principals",
                }) {
            ArrayNode records = state.putArray(collectionName);
            for (JsonNode record : seed.path(collectionName)) {
                if (record.path("workspaceId").asText().equals(fixtureWorkspaceId)) {
                    records.add(record.deepCopy());
                }
            }
        }
    }

    private void initializeOrders(ObjectNode state) {
        for (JsonNode record : state.path("orders")) {
            ObjectNode order = (ObjectNode) record;
            JsonNode address = find(seed.path("addresses"), str(order, "addressId"));
            ObjectNode deliveryAddress = (ObjectNode) address.deepCopy();
            deliveryAddress.remove(java.util.List.of("id", "workspaceId", "principalId"));
            order.set("deliveryAddress", deliveryAddress);
            order.put(
                    "placedAt",
                    Instant.now()
                            .plusSeconds(order.path("placedAtOffsetMinutes").asLong() * 60)
                            .toString());
        }
    }

    private void initializeShipments(ObjectNode state) {
        for (JsonNode record : state.path("shipments")) {
            ObjectNode shipment = (ObjectNode) record;
            JsonNode offset = shipment.get("estimatedDeliveryOffsetMinutes");
            if (offset == null || offset.isNull()) {
                shipment.putNull("estimatedDeliveryAt");
            } else {
                shipment.put(
                        "estimatedDeliveryAt",
                        Instant.now().plusSeconds(offset.asLong() * 60).toString());
            }
            for (JsonNode event : shipment.path("events")) {
                ((ObjectNode) event)
                        .put(
                                "occurredAt",
                                Instant.now()
                                        .plusSeconds(
                                                event.path("occurredAtOffsetMinutes").asLong() * 60)
                                        .toString());
            }
        }
    }

    public synchronized void ensure(String workspace, String pack) {
        tx.executeWithoutResult(
                t -> {
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM demo_workspace WHERE id=?",
                                    Integer.class,
                                    workspace)
                            == 0) {
                        ObjectNode initial = fresh(pack);
                        initial.put("workspaceId", workspace);
                        jdbc.update(
                                "INSERT INTO demo_workspace(id,document) VALUES (?,?)",
                                workspace,
                                initial.toString());
                        syncKnowledge(workspace, initial);
                    }
                });
    }

    public void syncKnowledge(String workspace, ObjectNode state) {
        jdbc.update("DELETE FROM knowledge_article WHERE workspace_id=?", workspace);
        for (JsonNode a : state.path("knowledgeArticles")) {
            jdbc.update(
                    "INSERT INTO knowledge_article(workspace_id,article_id,version,title,content) VALUES (?,?,?,?,?)",
                    workspace,
                    str(a, "id"),
                    a.path("version").asInt(),
                    str(a, "title"),
                    str(a, "content"));
        }
    }

    public <T> T read(String id, Function<ObjectNode, T> operation) {
        return operation.apply(load(id, false));
    }

    public <T> T change(String id, Function<ObjectNode, T> operation) {
        return tx.execute(
                t -> {
                    ObjectNode state = load(id, true);
                    T result = operation.apply(state);
                    jdbc.update(
                            "UPDATE demo_workspace SET document=?,revision=revision+1 WHERE id=?",
                            state.toString(),
                            id);
                    return result;
                });
    }

    private ObjectNode load(String id, boolean lock) {
        return (ObjectNode)
                parse(
                        jdbc.queryForObject(
                                "SELECT document FROM demo_workspace WHERE id=?"
                                        + (lock ? " FOR UPDATE" : ""),
                                String.class,
                                id));
    }

    public void purge(String workspace) {
        tx.executeWithoutResult(
                t -> {
                    ObjectNode state = load(workspace, true);
                    for (JsonNode a : state.path("actions")) {
                        if (java.util.Set.of("UNKNOWN", "EXECUTING").contains(str(a, "status"))) {
                            throw new com.chatty.api.Problem(
                                    409, "PURGE_BLOCKED", "Reconcile pending operations first.");
                        }
                    }
                    for (JsonNode run : state.path("runs")) {
                        if (java.util.Set.of("QUEUED", "RUNNING").contains(str(run, "status"))) {
                            throw new com.chatty.api.Problem(
                                    409, "PURGE_BLOCKED", "Stop active runs first.");
                        }
                    }
                    jdbc.update("DELETE FROM knowledge_article WHERE workspace_id=?", workspace);
                    jdbc.update("DELETE FROM demo_session WHERE workspace_id=?", workspace);
                    jdbc.update("DELETE FROM demo_workspace WHERE id=?", workspace);
                });
    }

    public void recover() {
        for (String w : jdbc.queryForList("SELECT id FROM demo_workspace", String.class)) {
            change(
                    w,
                    state -> {
                        for (JsonNode n : state.path("runs")) {
                            ObjectNode run = (ObjectNode) n;
                            if (java.util.Set.of("QUEUED", "RUNNING")
                                    .contains(str(run, "status"))) {
                                run.put("status", "FAILED");
                                event(
                                        run,
                                        "recovery",
                                        "Interrupted run recovered. Send a new message to retry.",
                                        obj("code", "PROCESS_RESTART"));
                            }
                        }
                        return null;
                    });
        }
    }

    public static void event(ObjectNode run, String type, String title, JsonNode detail) {
        ArrayNode events = (ArrayNode) run.withArray("events");
        events.add(
                obj(
                        "id",
                        events.size() + 1,
                        "runId",
                        str(run, "id"),
                        "type",
                        type,
                        "title",
                        title,
                        "at",
                        Instant.now().toString(),
                        "detail",
                        detail));
    }
}

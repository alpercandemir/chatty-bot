package com.chatty.scenario;

import com.chatty.model.Decision;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface ScenarioPack {
    String id();

    String instructions();

    Map<String, JsonNode> schemas();

    default java.util.List<ToolSpec> tools() {
        return schemas().entrySet().stream()
                .map(e -> ToolSpec.of(e.getKey(), e.getValue()))
                .toList();
    }

    default JsonNode manifest() {
        return com.chatty.store.Json.obj(
                "id",
                id(),
                "version",
                "1.0.0",
                "policyVersion",
                "1.0.0",
                "businessInstructions",
                instructions(),
                "tools",
                tools(),
                "adapterBinding",
                getClass().getSimpleName(),
                "knowledgeSource",
                "SCOPED_LOCAL_DATABASE",
                "fixture",
                "demo/fixtures/seed.json",
                "evaluationCases",
                "demo/scenarios.json");
    }

    Decision rehearse(ObjectNode conversation, ObjectNode run);

    JsonNode invoke(String tool, JsonNode args, ToolContext context);

    JsonNode execute(ObjectNode action, ToolContext context);
}

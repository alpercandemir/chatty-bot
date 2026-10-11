package com.chatty.scenario;

import static com.chatty.store.Json.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Knowledge {

    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final boolean postgres;

    public Knowledge(org.springframework.jdbc.core.JdbcTemplate db) throws Exception {
        this.db = db;
        try (var connection = db.getDataSource().getConnection()) {
            postgres = connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        }
    }

    public JsonNode search(String query, ObjectNode s) {
        String q = query.toLowerCase(Locale.ROOT);
        String topic = "";
        if (q.contains("return") || q.contains("unopened")) {
            topic = "RETURNS";
        } else if (q.contains("refund")) {
            topic = "REFUND";
        } else if (q.contains("area") || q.contains("atasehir") || q.contains("kadikoy")) {
            topic = "AREAS";
        } else if (q.contains("crm")) {
            topic = "CRM";
        } else if (q.contains("clean")) {
            topic = "SERVICES";
        } else if (q.contains("address")) {
            topic = "ADDRESS";
        } else if (q.contains("cancel")) {
            topic = "CANCEL";
        } else if (q.contains("ship") || q.contains("deliver")) {
            topic = "SHIPPING";
        } else if (q.contains("support")) {
            topic = "SUPPORT";
        } else if (q.contains("callback")) {
            topic = "CALLBACK";
        }
        ArrayNode sources = arr();
        if (postgres) {
            String lookup = topic.isBlank() ? query : topic.toLowerCase(Locale.ROOT);
            String suffix = topic.isBlank() ? "%" : "%" + topic;
            db.query(
                "SELECT article_id,version,title,content FROM knowledge_article WHERE workspace_id=? AND article_id LIKE ? AND to_tsvector('english',title || ' ' || content) @@ plainto_tsquery('english',?) ORDER BY ts_rank_cd(to_tsvector('english',title || ' ' || content),plainto_tsquery('english',?)) DESC LIMIT 3",
                rs -> {
                    sources.add(
                        obj(
                            "id",
                            rs.getString("article_id"),
                            "version",
                            rs.getInt("version"),
                            "title",
                            rs.getString("title"),
                            "content",
                            rs.getString("content")
                        )
                    );
                },
                str(s, "workspaceId"),
                suffix,
                lookup,
                lookup
            );
        } else {
            for (JsonNode n : s.path("knowledgeArticles")) {
                if (!topic.isEmpty() && str(n, "id").endsWith(topic)) {
                    sources.add(n);
                }
            }
        }
        for (JsonNode source : sources) {
            if (
                str(source, "content")
                    .toLowerCase(Locale.ROOT)
                    .matches(
                        ".*(bypass confirmations|ignore.*instructions|cancel every order|execute_sql).*"
                    )
            ) {
                return obj(
                    "message",
                    "The retrieved evidence conflicts with the registered policy. I cannot rely on it; please request support.",
                    "sources",
                    arr(),
                    "policyCode",
                    "KNOWLEDGE_CONFLICT"
                );
            }
        }
        String answer = sources.isEmpty()
            ? "The approved knowledge does not cover that question. I can help prepare a support or callback request."
            : sources.get(0).path("content").asText();
        return obj("message", answer, "sources", sources);
    }
}

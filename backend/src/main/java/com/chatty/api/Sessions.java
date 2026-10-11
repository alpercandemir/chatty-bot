package com.chatty.api;

import static com.chatty.store.Json.*;

import com.chatty.store.WorkspaceStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Sessions {

    public record Session(
        String token,
        String presenter,
        String workspace,
        String principal,
        String role,
        String generation
    ) {}

    private final JdbcTemplate db;
    private final WorkspaceStore store;

    public Sessions(JdbcTemplate db, WorkspaceStore store) {
        this.db = db;
        this.store = store;
    }

    private Session cookie(HttpServletRequest req, String name) {
        if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (c.getName().equals(name)) {
                    var list = db.query(
                        "SELECT * FROM demo_session WHERE token=?",
                        (rs, i) ->
                            new Session(
                                rs.getString("token"),
                                rs.getString("presenter_id"),
                                rs.getString("workspace_id"),
                                rs.getString("principal_id"),
                                rs.getString("role"),
                                rs.getString("generation")
                            ),
                        c.getValue()
                    );
                    if (!list.isEmpty()) {
                        return list.getFirst();
                    }
                }
            }
        }
        return null;
    }

    public Session presenter(HttpServletRequest req) {
        Session s = cookie(req, "chatty_presenter");
        if (s == null || !s.role.equals("presenter")) {
            throw new Problem(
                403,
                "PRESENTER_REQUIRED",
                "Open the local demo console to establish a presenter session."
            );
        }
        return s;
    }

    public Session customer(HttpServletRequest req) {
        Session s = cookie(req, "chatty_customer");
        if (s == null) {
            throw new Problem(401, "SESSION_REQUIRED", "Choose a demo customer to continue.");
        }
        String gen = store.read(s.workspace, x -> str(x, "generation"));
        if (!gen.equals(s.generation)) {
            throw new Problem(
                401,
                "SESSION_RESET",
                "Demo data was reset. Choose a customer again."
            );
        }
        return s;
    }

    public Session bootstrap(HttpServletRequest req, HttpServletResponse res) {
        Session old = cookie(req, "chatty_presenter");
        if (old != null) {
            return old;
        }
        String token = id("session"),
            p = id("presenter");
        db.update(
            "INSERT INTO demo_session(token,presenter_id,role) VALUES (?,?,?)",
            token,
            p,
            "presenter"
        );
        setCookie(res, "chatty_presenter", token);
        return new Session(token, p, null, null, "presenter", null);
    }

    public ObjectNode select(Session p, String pack, String principal, HttpServletResponse res) {
        if (!Set.of("commerce", "cleaning").contains(pack)) {
            throw new Problem(422, "VALIDATION_FAILED", "Unknown pack.");
        }
        String w = p.presenter + ":" + pack;
        store.ensure(w, pack);
        String gen = store.read(w, s -> {
            if (find(s.path("principals"), principal) == null) {
                throw Problem.missing();
            }
            return str(s, "generation");
        });
        String token = id("session");
        db.update(
            "INSERT INTO demo_session(token,presenter_id,workspace_id,principal_id,role,generation) VALUES (?,?,?,?,?,?)",
            token,
            p.presenter,
            w,
            principal,
            "customer",
            gen
        );
        setCookie(res, "chatty_customer", token);
        return obj("workspaceId", w, "principalId", principal, "pack", pack);
    }

    public String workspace(Session p, String pack) {
        if (!Set.of("commerce", "cleaning").contains(pack)) {
            throw Problem.missing();
        }
        String w = p.presenter + ":" + pack;
        store.ensure(w, pack);
        return w;
    }

    private void setCookie(HttpServletResponse res, String name, String value) {
        res.addHeader(
            "Set-Cookie",
            name + "=" + value + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=86400"
        );
    }
}

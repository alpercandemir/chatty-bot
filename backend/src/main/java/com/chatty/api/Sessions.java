package com.chatty.api;

import static com.chatty.store.Json.find;
import static com.chatty.store.Json.id;
import static com.chatty.store.Json.obj;
import static com.chatty.store.Json.str;

import com.chatty.store.WorkspaceStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

@Component
public class Sessions {

    public record Session(
            String token,
            String presenter,
            String workspace,
            String principal,
            String role,
            String generation) {}

    private final JdbcTemplate db;
    private final WorkspaceStore store;

    public Sessions(JdbcTemplate db, WorkspaceStore store) {
        this.db = db;
        this.store = store;
    }

    private Session cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (!cookie.getName().equals(name)) {
                continue;
            }
            var matchingSessions =
                    db.query(
                            "SELECT * FROM demo_session WHERE token=?",
                            (result, rowNumber) ->
                                    new Session(
                                            result.getString("token"),
                                            result.getString("presenter_id"),
                                            result.getString("workspace_id"),
                                            result.getString("principal_id"),
                                            result.getString("role"),
                                            result.getString("generation")),
                            cookie.getValue());
            if (!matchingSessions.isEmpty()) {
                return matchingSessions.getFirst();
            }
        }
        return null;
    }

    public Session presenter(HttpServletRequest request) {
        Session session = cookie(request, "chatty_presenter");
        if (session == null || !session.role.equals("presenter")) {
            throw new Problem(
                    403,
                    "PRESENTER_REQUIRED",
                    "Open the local demo console to establish a presenter session.");
        }
        return session;
    }

    public Session customer(HttpServletRequest request) {
        Session session = cookie(request, "chatty_customer");
        if (session == null) {
            throw new Problem(401, "SESSION_REQUIRED", "Choose a demo customer to continue.");
        }
        String generation = store.read(session.workspace, state -> str(state, "generation"));
        if (!generation.equals(session.generation)) {
            throw new Problem(
                    401, "SESSION_RESET", "Demo data was reset. Choose a customer again.");
        }
        return session;
    }

    public Session bootstrap(HttpServletRequest request, HttpServletResponse response) {
        Session existingSession = cookie(request, "chatty_presenter");
        if (existingSession != null) {
            return existingSession;
        }
        String token = id("session");
        String presenterId = id("presenter");
        db.update(
                "INSERT INTO demo_session(token,presenter_id,role) VALUES (?,?,?)",
                token,
                presenterId,
                "presenter");
        setCookie(response, "chatty_presenter", token);
        return new Session(token, presenterId, null, null, "presenter", null);
    }

    public ObjectNode select(
            Session presenterSession, String pack, String principal, HttpServletResponse response) {
        if (!Set.of("commerce", "cleaning").contains(pack)) {
            throw new Problem(422, "VALIDATION_FAILED", "Unknown pack.");
        }
        String workspaceId = presenterSession.presenter + ":" + pack;
        store.ensure(workspaceId, pack);
        String generation =
                store.read(
                        workspaceId,
                        state -> {
                            if (find(state.path("principals"), principal) == null) {
                                throw Problem.missing();
                            }
                            return str(state, "generation");
                        });
        String token = id("session");
        db.update(
                "INSERT INTO demo_session(token,presenter_id,workspace_id,principal_id,role,generation) VALUES (?,?,?,?,?,?)",
                token,
                presenterSession.presenter,
                workspaceId,
                principal,
                "customer",
                generation);
        setCookie(response, "chatty_customer", token);
        return obj("workspaceId", workspaceId, "principalId", principal, "pack", pack);
    }

    public String workspace(Session presenterSession, String pack) {
        if (!Set.of("commerce", "cleaning").contains(pack)) {
            throw Problem.missing();
        }
        String workspaceId = presenterSession.presenter + ":" + pack;
        store.ensure(workspaceId, pack);
        return workspaceId;
    }

    private void setCookie(HttpServletResponse response, String name, String value) {
        response.addHeader(
                "Set-Cookie",
                name + "=" + value + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=86400");
    }
}

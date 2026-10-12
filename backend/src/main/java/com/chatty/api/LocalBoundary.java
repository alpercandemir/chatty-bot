package com.chatty.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalBoundary extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        String host = request.getServerName();
        if (!Set.of("localhost", "127.0.0.1", "::1").contains(host)) {
            response.sendError(403, "Local demonstration only");
            return;
        }
        if (origin != null) {
            try {
                URI originUri = URI.create(origin);
                if (!Set.of("localhost", "127.0.0.1", "::1").contains(originUri.getHost())
                        || !Set.of(80, 8080, 5173, 4173)
                                .contains(originUri.getPort() == -1 ? 80 : originUri.getPort())) {
                    response.sendError(403, "Origin rejected");
                    return;
                }
            } catch (Exception e) {
                response.sendError(403);
                return;
            }
        }
        String path = request.getRequestURI();
        if (path.startsWith("/swagger") || path.startsWith("/v3/api-docs")) {
            try {
                sessions.presenter(request);
            } catch (Problem e) {
                response.sendError(403);
                return;
            }
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private final Sessions sessions;

    public LocalBoundary(Sessions sessions) {
        this.sessions = sessions;
    }
}

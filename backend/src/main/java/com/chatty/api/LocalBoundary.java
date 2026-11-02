package com.chatty.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalBoundary extends OncePerRequestFilter {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private final Sessions sessions;
    private final URI publicOrigin;

    public LocalBoundary(
            Sessions sessions, @Value("${chatty.public-origin:}") String publicOrigin) {
        this.sessions = sessions;
        this.publicOrigin = publicOrigin.isBlank() ? null : URI.create(publicOrigin);
        if (this.publicOrigin != null
                && (!"https".equals(this.publicOrigin.getScheme())
                        || this.publicOrigin.getHost() == null
                        || this.publicOrigin.getRawUserInfo() != null
                        || !this.publicOrigin.getRawPath().isEmpty()
                        || this.publicOrigin.getRawQuery() != null
                        || this.publicOrigin.getRawFragment() != null)) {
            throw new IllegalArgumentException(
                    "The public origin must be an HTTPS origin without a path.");
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        String host = request.getServerName();
        if (!LOCAL_HOSTS.contains(host)
                && (publicOrigin == null || !publicOrigin.getHost().equalsIgnoreCase(host))) {
            response.sendError(403, "Host rejected");
            return;
        }
        if (origin != null) {
            try {
                URI originUri = URI.create(origin);
                boolean localOrigin =
                        originUri.getHost() != null
                                && LOCAL_HOSTS.contains(originUri.getHost())
                                && Set.of("http", "https").contains(originUri.getScheme())
                                && Set.of(80, 8080, 5173, 4173)
                                        .contains(
                                                originUri.getPort() == -1
                                                        ? 80
                                                        : originUri.getPort());
                if (!localOrigin && !originUri.equals(publicOrigin)) {
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
}

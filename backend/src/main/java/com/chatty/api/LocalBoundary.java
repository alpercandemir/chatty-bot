package com.chatty.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
@Component
public class LocalBoundary extends OncePerRequestFilter {
  @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
    String origin=req.getHeader("Origin"),host=req.getServerName();
    if(!Set.of("localhost","127.0.0.1","::1").contains(host)){res.sendError(403,"Local demonstration only");return;}
    if(origin!=null){try{URI u=URI.create(origin);if(!Set.of("localhost","127.0.0.1","::1").contains(u.getHost())||!Set.of(80,8080,5173,4173).contains(u.getPort()==-1?80:u.getPort())){res.sendError(403,"Origin rejected");return;}}catch(Exception e){res.sendError(403);return;}}
    String path=req.getRequestURI();if(path.startsWith("/swagger")||path.startsWith("/v3/api-docs")){try{sessions.presenter(req);}catch(Problem e){res.sendError(403);return;}}
    res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","same-origin");res.setHeader("Cache-Control","no-store");chain.doFilter(req,res);
  }
  private final Sessions sessions;public LocalBoundary(Sessions s){sessions=s;}
}

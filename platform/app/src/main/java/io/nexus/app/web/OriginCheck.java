package io.nexus.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The API has no login (Spec 16). So that a page on another site cannot change the plant through a visitor's
 * browser (cross-site request forgery), every request that changes something must come from this server's own page,
 * from an origin in nexus.ws.allowed-origins (the Vite dev servers in the embedded profile), or without an Origin
 * header at all (curl, tests, other servers; browsers always send one on cross-site writes).
 */
@Component
class OriginCheck extends OncePerRequestFilter {
  private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS");

  private final List<String> allowed;

  OriginCheck(@Value("${nexus.ws.allowed-origins:}") List<String> allowed) {
    this.allowed = allowed;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    if (origin == null || READING.contains(request.getMethod()) || origin.equals(ownOrigin(request))
        || allowed.contains(origin)) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Fremde Seite darf die Anlage nicht steuern\"}");
  }

  private static String ownOrigin(HttpServletRequest r) {
    int port = r.getServerPort();
    boolean defaultPort = ("http".equals(r.getScheme()) && port == 80) || ("https".equals(r.getScheme()) && port == 443);
    return r.getScheme() + "://" + r.getServerName() + (defaultPort ? "" : ":" + port);
  }
}

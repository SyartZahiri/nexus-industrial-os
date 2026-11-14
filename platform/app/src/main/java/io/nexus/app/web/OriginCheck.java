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
 * The API has no login (Spec 16) and is meant for this computer. Two checks keep other web pages out:
 * <ul>
 *   <li>Host: only localhost and the hosts in nexus.allowed-hosts are served, for every request. Otherwise a domain
 *       that resolves to 127.0.0.1 (DNS rebinding) could read and steer the plant as if it were this page.</li>
 *   <li>Origin: a request that changes something must come from this server's own page, from an origin in
 *       nexus.ws.allowed-origins (the Vite dev servers in the embedded profile), or without an Origin at all (curl,
 *       tests, other servers; browsers always send one on cross-site writes).</li>
 * </ul>
 */
@Component
class OriginCheck extends OncePerRequestFilter {
  private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS");
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  private final List<String> allowedOrigins;
  private final List<String> allowedHosts;

  OriginCheck(@Value("${nexus.ws.allowed-origins:}") List<String> allowedOrigins,
              @Value("${nexus.allowed-hosts:}") List<String> allowedHosts) {
    this.allowedOrigins = allowedOrigins;
    this.allowedHosts = allowedHosts;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String host = request.getServerName();
    if (!LOCAL_HOSTS.contains(host) && !allowedHosts.contains(host)) {
      refuse(response, "Unbekannter Host");
      return;
    }
    String origin = request.getHeader("Origin");
    if (origin == null || READING.contains(request.getMethod()) || origin.equals(ownOrigin(request))
        || allowedOrigins.contains(origin)) {
      chain.doFilter(request, response);
      return;
    }
    refuse(response, "Fremde Seite darf die Anlage nicht steuern");
  }

  private static void refuse(HttpServletResponse response, String detail) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"" + detail + "\"}");
  }

  private static String ownOrigin(HttpServletRequest r) {
    int port = r.getServerPort();
    boolean defaultPort = ("http".equals(r.getScheme()) && port == 80) || ("https".equals(r.getScheme()) && port == 443);
    return r.getScheme() + "://" + r.getServerName() + (defaultPort ? "" : ":" + port);
  }
}

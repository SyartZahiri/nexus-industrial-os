package io.nexus.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Security review: DNS rebinding lets a foreign domain resolve to 127.0.0.1; then Origin and Host both name that
 * domain and agree. Only hosts this server is meant to be reached under are served.
 */
class OriginCheckHostTest {
  private final OriginCheck check = new OriginCheck(List.of("http://localhost:5173"), List.of("nexus.example"));

  private int status(String method, String host, String origin) throws Exception {
    var request = new MockHttpServletRequest(method, "/api/v1/sim/play");
    String name = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
    request.setServerName(name);
    request.setServerPort(host.contains(":") ? Integer.parseInt(host.substring(host.indexOf(':') + 1)) : 80);
    request.addHeader("Host", host);
    if (origin != null) request.addHeader("Origin", origin);
    var response = new MockHttpServletResponse();
    check.doFilter(request, response, new MockFilterChain());
    return response.getStatus();
  }

  @Test
  void aRebindingDomainIsRefusedEvenWhenOriginAndHostAgree() throws Exception {
    assertThat(status("POST", "evil.example:8080", "http://evil.example:8080")).isEqualTo(403);
    assertThat(status("GET", "evil.example:8080", null)).as("reading is refused too").isEqualTo(403);
  }

  @Test
  void localhostAndConfiguredHostsAreServed() throws Exception {
    assertThat(status("POST", "localhost:8080", "http://localhost:8080")).isEqualTo(200);
    assertThat(status("POST", "127.0.0.1:8080", null)).isEqualTo(200);
    assertThat(status("GET", "nexus.example", null)).isEqualTo(200);
    assertThat(status("POST", "localhost:8080", "http://localhost:5173")).isEqualTo(200);
  }
}

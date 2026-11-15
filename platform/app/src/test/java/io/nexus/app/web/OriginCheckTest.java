package io.nexus.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Security review: the API has no login (Spec 16), so a page on another site must not be able to change the plant
 * through the visitor's browser. Browsers send Origin with every cross-site POST; tools without a browser send none.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
class OriginCheckTest {
  @Value("${local.server.port}") int port;
  final HttpClient http = HttpClient.newHttpClient();

  int post(String path, String origin) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).POST(HttpRequest.BodyPublishers.noBody());
    if (origin != null) b.header("Origin", origin);
    return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @AfterEach
  void pause() throws Exception {
    post("/api/v1/sim/pause", null);
  }

  @Test
  void aForeignPageCannotChangeThePlant() throws Exception {
    assertThat(post("/api/v1/sim/play", "http://evil.example")).isEqualTo(403);
    assertThat(post("/api/v1/machines/CNC-01/stop", "https://evil.example")).isEqualTo(403);
  }

  @Test
  void theOwnPageTheDevServerAndToolsWithoutOriginMay() throws Exception {
    assertThat(post("/api/v1/sim/play", "http://localhost:" + port)).isEqualTo(204);
    assertThat(post("/api/v1/sim/play", "http://localhost:5173")).isEqualTo(204);
    assertThat(post("/api/v1/sim/play", null)).isEqualTo(204);
  }
}

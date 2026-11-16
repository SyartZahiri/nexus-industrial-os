package io.nexus.app.plant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
class ScenarioApiTest {
  @Value("${local.server.port}") int port;
  @Autowired SimulationLoop loop;
  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> send(String method, String path) throws Exception {
    var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
        .method(method, method.equals("POST") && path.endsWith("/reset")
            ? HttpRequest.BodyPublishers.ofString("{\"seed\":11}") : HttpRequest.BodyPublishers.noBody());
    return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
  }

  @BeforeEach
  void freshPlant() throws Exception {
    assertThat(send("POST", "/api/v1/sim/reset").statusCode()).isEqualTo(204);
  }

  @AfterEach
  void stop() {
    loop.pause();
    loop.setSpeed(1);
  }

  @Test
  void listsAndStartsScenarios() throws Exception {
    var list = send("GET", "/api/v1/scenarios");
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).contains("\"id\":\"A\"").contains("\"id\":\"E\"").contains("\"runs\":[]");

    var started = send("POST", "/api/v1/scenarios/D/run");
    assertThat(started.statusCode()).isEqualTo(200);
    assertThat(started.body()).contains("\"runId\":\"SZ-1\"");

    assertThat(send("POST", "/api/v1/scenarios/E/run").statusCode()).isEqualTo(409);
    assertThat(send("POST", "/api/v1/scenarios/Z/run").statusCode()).isEqualTo(404);
  }
}

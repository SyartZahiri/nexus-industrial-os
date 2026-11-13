package io.nexus.app.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.plant.Plant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
class SimApiTest {
  @Value("${local.server.port}") int port;
  @Autowired SimulationLoop loop;
  @Autowired Plant plant;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void freshPlant() throws Exception {
    assertThat(send("POST", "/api/v1/sim/reset", "{\"seed\":11}").statusCode()).isEqualTo(204);
  }

  /** The loop is a singleton of the shared test context: never leave it running for other test classes. */
  @AfterEach
  void stop() {
    loop.pause();
    loop.setSpeed(1);
  }

  HttpResponse<String> send(String method, String path, String json) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
    b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody()) : b.method(method, HttpRequest.BodyPublishers.ofString(json));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  String rules(boolean autoOrders) {
    return "{\"randomFaults\":false,\"autoRepair\":true,\"autoMaintenance\":true,\"maintenanceThreshold\":0.85,"
        + "\"autoOrders\":" + autoOrders + ",\"autoOrderIntervalMinutes\":6,\"maxOpenOrders\":6,\"shipping\":true,"
        + "\"dispatchRule\":\"PRIORITY\"}";
  }

  @Test
  void stateShowsClockSeedAndRules() throws Exception {
    var r = send("GET", "/api/v1/sim", null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"simTimeMs\":0").contains("\"running\":false").contains("\"seed\":11")
        .contains("\"randomFaults\"").contains("\"dispatchRule\":\"PRIORITY\"");
  }

  @Test
  void playPauseAndSpeed() throws Exception {
    assertThat(send("PUT", "/api/v1/sim/speed", "{\"speed\":25}").statusCode()).isEqualTo(204);
    assertThat(send("POST", "/api/v1/sim/play", null).statusCode()).isEqualTo(204);
    Thread.sleep(600);
    assertThat(send("POST", "/api/v1/sim/pause", null).statusCode()).isEqualTo(204);
    long t = plant.nowMs();
    assertThat(t).isPositive();
    Thread.sleep(200);
    assertThat(plant.nowMs()).isEqualTo(t);
  }

  @Test
  void invalidInputIs400() throws Exception {
    assertThat(send("PUT", "/api/v1/sim/speed", "{\"speed\":0}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", "/api/v1/sim/speed", "{\"speed\":30}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", "/api/v1/sim/rules", "{\"maxOpenOrders\":0}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", "/api/v1/sim/rules", rules(false).replace("PRIORITY", "RANDOM")).statusCode()).isEqualTo(400);
  }

  @Test
  void rulesAreApplied() throws Exception {
    assertThat(send("PUT", "/api/v1/sim/rules", rules(false)).statusCode()).isEqualTo(204);
    assertThat(plant.rules().autoOrders()).isFalse();
    assertThat(plant.rules().machines().randomFaults()).isFalse();
  }

  @Test
  void frameHasTheWholePlant() throws Exception {
    var r = send("GET", "/api/v1/sim/frame", null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/json");
    assertThat(r.body().split("\"spindle\"", -1)).hasSize(8);           // seven machines
    assertThat(r.body().split("\"battery\"", -1)).hasSize(7);           // six AGVs
  }

  @Test
  void anOrderPlacedOverRestIsCompletedWhileTheLoopRuns() throws Exception {
    send("PUT", "/api/v1/sim/rules", rules(false));
    var created = send("POST", "/api/v1/orders", "{\"productId\":\"FP12\",\"quantity\":2,\"priority\":\"EILIG\",\"dueInMinutes\":120}");
    assertThat(created.statusCode()).isEqualTo(201);
    send("PUT", "/api/v1/sim/speed", "{\"speed\":25}");
    send("POST", "/api/v1/sim/play", null);
    long deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline && !send("GET", "/api/v1/orders", null).body().contains("\"COMPLETED\"")) {
      Thread.sleep(500);
    }
    assertThat(send("GET", "/api/v1/orders", null).body()).contains("\"COMPLETED\"");
  }

  /** Review Focus 1: commands arrive while steps run; each waits for the lock, none is lost or doubled. */
  @Test
  void commandsDuringRunningStepsAreAllApplied() throws Exception {
    send("PUT", "/api/v1/sim/rules", rules(false));
    send("PUT", "/api/v1/sim/speed", "{\"speed\":25}");
    send("POST", "/api/v1/sim/play", null);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Integer>> codes = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        codes.add(pool.submit(() -> send("POST", "/api/v1/orders",
            "{\"productId\":\"AW50\",\"quantity\":1,\"priority\":\"NORMAL\",\"dueInMinutes\":600}").statusCode()));
      }
      for (Future<Integer> c : codes) assertThat(c.get(60, TimeUnit.SECONDS)).isEqualTo(201);
    } finally {
      pool.shutdownNow();
    }
    loop.pause();
    String orders = send("GET", "/api/v1/orders", null).body();
    assertThat(orders.split("\"productId\":\"AW50\"", -1)).hasSize(21);
  }
}

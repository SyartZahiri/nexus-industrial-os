package io.nexus.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.app.event.SimRun;
import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.state.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
@Import(ApiTest.Probe.class)
class ApiTest {
  record ProbeInput(@NotBlank String product, @Min(1) int quantity) {}

  @TestConfiguration
  @RestController
  static class Probe {
    @Autowired JdbcClient jdbc;

    @GetMapping("/api/v1/probe/missing/{id}")
    String missing(@PathVariable String id) { throw DomainException.notFound("Auftrag", id); }

    @GetMapping("/api/v1/probe/invalid")
    String invalid() { throw DomainException.invalid("Menge muss positiv sein"); }

    @GetMapping("/api/v1/probe/illegal")
    String illegal() { return OrderStatus.RULES.move("PO-1", OrderStatus.CREATED, OrderStatus.COMPLETED).name(); }

    @PostMapping("/api/v1/probe/validate")
    String validate(@Valid @RequestBody ProbeInput in) { return in.product(); }

    @GetMapping("/api/v1/probe/crash")
    String crash() { throw new IllegalStateException("jdbc:postgresql://geheim"); }

    @GetMapping("/api/v1/probe/library")
    String library() { throw new IllegalArgumentException("internes Detail"); }

    @GetMapping("/api/v1/probe/overbook")
    String overbook() {
      jdbc.sql("update stock set reserved = on_hand + 1 where material_id = 'RM-GH200'").update();
      return "ok";
    }
  }

  @Value("${local.server.port}") int port;
  @Autowired EventPublisher events;
  @Autowired JdbcClient jdbc;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void clean() {
    jdbc.sql("delete from event_delivery_failure").update();
    jdbc.sql("delete from processed_event").update();
    jdbc.sql("delete from event").update();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void orderCreated(String orderId) {
    events.publish(EventTypes.ORDER_CREATED, "smartfactory", Severity.INFO, orderId,
        new Payloads.OrderCreated(orderId, "GH200", 5, Priority.NORMAL, 0));
  }

  private static void assertProblem(HttpResponse<String> r, int status) {
    assertThat(r.statusCode()).isEqualTo(status);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
  }

  @Test
  void unknownIdIs404ProblemJson() throws Exception {
    var r = get("/api/v1/probe/missing/PO-9");
    assertProblem(r, 404);
    assertThat(r.body()).contains("PO-9");
  }

  @Test
  void invalidBusinessInputIs400WithItsMessage() throws Exception {
    var r = get("/api/v1/probe/invalid");
    assertProblem(r, 400);
    assertThat(r.body()).contains("Menge muss positiv sein");
  }

  @Test
  void illegalTransitionIs409() throws Exception {
    var r = get("/api/v1/probe/illegal");
    assertProblem(r, 409);
    assertThat(r.body()).contains("CREATED").contains("COMPLETED");
  }

  @Test
  void validationErrorIs400AndNamesTheFields() throws Exception {
    var r = post("/api/v1/probe/validate", "{\"product\":\"\",\"quantity\":0}");
    assertProblem(r, 400);
    assertThat(r.body()).contains("\"errors\"").contains("product").contains("quantity");
  }

  @Test
  void malformedJsonIs400NotAServerError() throws Exception {
    assertThat(post("/api/v1/probe/validate", "{not json").statusCode()).isEqualTo(400);
  }

  @Test
  void unexpectedErrorIs500ProblemJsonWithoutInternalText() throws Exception {
    var r = get("/api/v1/probe/crash");
    assertProblem(r, 500);
    assertThat(r.body()).doesNotContain("geheim").doesNotContain("postgresql");
  }

  @Test
  void illegalArgumentFromALibraryIsNotEchoed() throws Exception {
    var r = get("/api/v1/probe/library");
    assertProblem(r, 500);
    assertThat(r.body()).doesNotContain("internes Detail");
  }

  @Test
  void databaseRuleViolationIs409WithoutSqlDetails() throws Exception {
    var r = get("/api/v1/probe/overbook");
    assertProblem(r, 409);
    assertThat(r.body()).doesNotContain("stock_reserved_within_on_hand").doesNotContain("update stock");
  }

  @Test
  void eventsAreFilterableAndPageableBySeq() throws Exception {
    orderCreated("PO-1");
    events.publish(EventTypes.TRANSPORT_CREATED, "flowpilot", Severity.INFO, "PO-1",
        new Payloads.TransportCreated("TO-1", TransportKind.SUPPLY, "PO-1", "RM-GH200", 5,
            Place.storage("RAW-A"), Place.machineIn("CNC-01")));
    orderCreated("PO-2");

    assertThat(get("/api/v1/events").body()).contains("PO-1").contains("PO-2").contains("transport.created");
    var onlyPo1 = get("/api/v1/events?correlationId=PO-1").body();
    assertThat(onlyPo1).contains("transport.created").doesNotContain("PO-2");
    assertThat(get("/api/v1/events?type=transport.created&source=flowpilot").body()).doesNotContain("order.created");

    long firstSeq = jdbc.sql("select min(seq) from event").query(Long.class).single();
    var afterFirst = get("/api/v1/events?after=" + firstSeq + "&limit=1").body();
    assertThat(afterFirst).contains("transport.created").doesNotContain("PO-2");
  }

  @Test
  void eventPayloadIsReturnedAsStructuredJson() throws Exception {
    orderCreated("PO-1");
    assertThat(get("/api/v1/events").body()).contains("\"productId\":\"GH200\"").contains("\"priority\":\"NORMAL\"");
  }

  @Test
  void eventsOfEarlierRunsAreNotMixedIntoTheCurrentRun(@Autowired SimRun run) throws Exception {
    orderCreated("PO-OLD");
    run.restart(run.seed());
    orderCreated("PO-NEW");
    assertThat(get("/api/v1/events").body()).contains("PO-NEW").doesNotContain("PO-OLD");
  }

  @Test
  void limitIsClampedInsteadOfFailing() throws Exception {
    assertThat(get("/api/v1/events?limit=999999").statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/events?limit=0").statusCode()).isEqualTo(200);
  }

  @Test
  void openApiDocumentListsTheEventsEndpoint() throws Exception {
    var r = get("/v3/api-docs");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("/api/v1/events");
  }
}

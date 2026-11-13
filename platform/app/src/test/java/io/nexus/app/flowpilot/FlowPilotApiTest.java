package io.nexus.app.flowpilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.flowpilot.FlowPilot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * FlowPilot's REST API. Own context: the FlowPilot bean keeps vehicles in memory, other tests reset the shared
 * database under it. Tests here do not depend on each other's order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "nexus.test.context=flowpilot-api")
@ActiveProfiles("embedded")
class FlowPilotApiTest {
  @Value("${local.server.port}") int port;
  @Autowired MaterialService materials;
  @Autowired TransportService transports;
  @Autowired FlowPilot flowPilot;
  final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (json != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
    else b.POST(HttpRequest.BodyPublishers.noBody());
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void beansAreTheFacadePorts() {
    assertThat(materials).isSameAs(flowPilot.materials());
    assertThat(transports).isSameAs(flowPilot.transports());
  }

  @Test
  void materialsShowAvailabilityPerLocation() throws Exception {
    var r = get("/api/v1/materials");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"id\":\"RM-GH200\"").contains("\"reorderPoint\":12").contains("\"locationId\":\"RAW-A\"")
        .contains("\"available\"");
  }

  @Test
  void stockLinesAreListed() throws Exception {
    var r = get("/api/v1/stock");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"materialId\":\"KIT-MT7\"").contains("\"onHand\"").contains("\"reserved\"");
  }

  @Test
  void reservationsOfAnOrderAreListed() throws Exception {
    materials.reserve(new ReservationRequest("API-REQ-1", "PO-API-1", List.of(new Line("RM-MT7", 1))));
    var r = get("/api/v1/reservations?orderId=PO-API-1");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"requestId\":\"API-REQ-1\"").contains("\"status\":\"ACTIVE\"");
    assertThat(get("/api/v1/reservations").statusCode()).isEqualTo(400);
  }

  @Test
  void receiptIsBookedAndValidated() throws Exception {
    var ok = post("/api/v1/stock/receipts", "{\"materialId\":\"RM-AW50\",\"locationId\":\"RAW-B\",\"quantity\":2}");
    assertThat(ok.statusCode()).isEqualTo(204);
    assertThat(get("/api/v1/stock").body()).contains("{\"materialId\":\"RM-AW50\",\"locationId\":\"RAW-B\"");
    assertThat(post("/api/v1/stock/receipts", "{\"materialId\":\"RM-AW50\",\"locationId\":\"RAW-B\",\"quantity\":0}")
        .statusCode()).isEqualTo(400);
    assertThat(post("/api/v1/stock/receipts", "{\"materialId\":\"NOPE\",\"locationId\":\"RAW-B\",\"quantity\":1}")
        .statusCode()).isEqualTo(404);
    assertThat(post("/api/v1/stock/receipts", "{\"materialId\":\"FG-FP12\",\"locationId\":\"FG-A\",\"quantity\":1000}")
        .statusCode()).isEqualTo(409);
  }

  @Test
  void transportsAreListedAndFilteredByStatus() throws Exception {
    String id = transports.requestTransport(new TransportRequest("API-TR-1", TransportKind.WIP, "PO-API-2", null, 1,
        Place.machineOut("CNC-01"), Place.machineIn("CNC-02"), Priority.NORMAL, null));
    assertThat(get("/api/v1/transports").body()).contains(id);
    assertThat(get("/api/v1/transports?status=CREATED").body()).contains(id).contains("\"fromNode\":\"D_CNC01\"");
    assertThat(get("/api/v1/transports?status=DELIVERED").body()).doesNotContain(id);
    assertThat(get("/api/v1/transports?status=NOPE").statusCode()).isEqualTo(400);
  }

  @Test
  void agvsAreListedAndCommanded() throws Exception {
    var r = get("/api/v1/agvs");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("AGV-01").contains("AGV-06").contains("\"battery\"").contains("\"heading\"");
    assertThat(post("/api/v1/agvs/AGV-06/disable", null).statusCode()).isEqualTo(204);
    assertThat(post("/api/v1/agvs/AGV-06/enable", null).statusCode()).isEqualTo(204);
    assertThat(post("/api/v1/agvs/AGV-04/charge", null).statusCode()).isEqualTo(204);
    assertThat(post("/api/v1/agvs/AGV-99/enable", null).statusCode()).isEqualTo(404);
  }

  @Test
  void faultIsInjectedAndUnknownAgvIs404() throws Exception {
    assertThat(post("/api/v1/agvs/AGV-05/fault", "{}").statusCode()).isEqualTo(204);
    assertThat(flowPilot.vehicles().get(4).status()).isEqualTo(io.nexus.contracts.state.AgvStatus.FAULT);
    assertThat(post("/api/v1/agvs/AGV-99/fault", "{\"faultTypeId\":\"NAV\"}").statusCode()).isEqualTo(404);
    assertThat(post("/api/v1/agvs/AGV-03/fault", "{\"faultTypeId\":\"BELT\"}").statusCode()).isEqualTo(400);
  }

  @Test
  void edgesAreBlockedAndUnblocked() throws Exception {
    assertThat(post("/api/v1/routes/edges/LN_-2%3ELN_2/block", null).statusCode()).isEqualTo(204);
    assertThat(get("/api/v1/layout").body()).contains("{\"id\":\"LN_-2>LN_2\",\"from\":\"LN_-2\",\"to\":\"LN_2\",\"length\":4.0,\"blocked\":true}");
    assertThat(post("/api/v1/routes/edges/LN_-2%3ELN_2/unblock", null).statusCode()).isEqualTo(204);
    assertThat(post("/api/v1/routes/edges/X%3EY/block", null).statusCode()).isEqualTo(404);
  }

  @Test
  void layoutHasNodesEdgesDocksAndChargers() throws Exception {
    var body = get("/api/v1/layout").body();
    assertThat(body).contains("\"nodes\"").contains("\"edges\"").contains("\"D_RAW\"").contains("\"CH-6\"")
        .contains("\"machineDocks\"").contains("\"conveyorDocks\"").contains("\"locations\"");
  }

  @Test
  void openApiListsTheTransportEndpoint() throws Exception {
    assertThat(get("/v3/api-docs").body()).contains("/api/v1/transports").contains("/api/v1/agvs/{id}/fault");
  }
}

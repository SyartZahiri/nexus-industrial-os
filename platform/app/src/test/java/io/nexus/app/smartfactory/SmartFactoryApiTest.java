package io.nexus.app.smartfactory;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.SmartFactory;
import io.nexus.smartfactory.testkit.FakeMachineControl;
import io.nexus.smartfactory.testkit.FakeMaterialService;
import io.nexus.smartfactory.testkit.FakeTransportService;
import io.nexus.smartfactory.testkit.FakeTransportService.FakeTransport;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** FlowPilot and the simulation are not on this branch: their ports come from the testkit fakes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
@Import(SmartFactoryApiTest.Ports.class)
class SmartFactoryApiTest {
  @TestConfiguration
  static class Ports {
    // @Primary: with FlowPilot in the context, SmartFactory must still talk to the fakes in this test
    @Bean @Primary FakeMaterialService fakeMaterialService() { return new FakeMaterialService(); }
    @Bean @Primary FakeTransportService fakeTransportService(EventPublisher events) { return new FakeTransportService(events); }
    @Bean FakeMachineControl machineControl() { return new FakeMachineControl(); }
  }

  @Value("${local.server.port}") int port;
  @Autowired JdbcClient jdbc;
  @Autowired FakeTransportService transports;
  @Autowired FakeMachineControl machines;
  @Autowired MachineFeedback feedback;
  @Autowired ProductCatalog catalog;
  @Autowired SmartFactory factory;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void clean() {
    SmartFactoryTables.clear(jdbc);
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
        .header("Content-Type", "application/json").method(method, body).build(), HttpResponse.BodyHandlers.ofString());
  }

  private String createdId(HttpResponse<String> r) {
    String location = r.headers().firstValue("Location").orElseThrow();
    return location.substring(location.lastIndexOf('/') + 1);
  }

  @Test
  void beansArePublishedForTheOtherModules() {
    assertThat(feedback).isSameAs(factory);
    assertThat(catalog.finishedMaterialId("GH200")).isEqualTo("FG-GH200");
  }

  @Test
  void creatingAnOrderAnswers201WithItsLocation() throws Exception {
    var r = send("POST", "/orders", "{\"productId\":\"GH200\",\"quantity\":5,\"priority\":\"HOCH\",\"dueInMinutes\":120}");
    assertThat(r.statusCode()).isEqualTo(201);
    String id = createdId(r);
    assertThat(r.headers().firstValue("Location").orElseThrow()).endsWith("/api/v1/orders/" + id);
    assertThat(r.body()).contains("\"id\":\"" + id + "\"").contains("MATERIAL_PENDING");

    var detail = send("GET", "/orders/" + id, null);
    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(detail.body()).contains("\"operations\"").contains("CNC-01").contains("\"materials\"").contains("RM-GH200");
    assertThat(send("GET", "/orders", null).body()).contains(id);
    assertThat(send("GET", "/schedule", null).body()).contains("\"rule\":\"PRIORITY\"").contains("bottleneckMachineId");
  }

  @Test
  void quantityZeroIs400NamingTheField() throws Exception {
    var r = send("POST", "/orders", "{\"productId\":\"GH200\",\"quantity\":0,\"priority\":\"NORMAL\",\"dueInMinutes\":60}");
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(r.body()).contains("\"field\":\"quantity\"");
    assertThat(send("GET", "/orders", null).body()).isEqualTo("[]");
  }

  @Test
  void unknownProductIs404() throws Exception {
    var r = send("POST", "/orders", "{\"productId\":\"XX-1\",\"quantity\":1,\"priority\":\"NORMAL\",\"dueInMinutes\":60}");
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(send("GET", "/orders/PO-9999", null).statusCode()).isEqualTo(404);
  }

  @Test
  void changingAndCancellingAnOrder() throws Exception {
    String id = createdId(send("POST", "/orders", "{\"productId\":\"FP12\",\"quantity\":2,\"dueInMinutes\":60}"));
    var patched = send("PATCH", "/orders/" + id, "{\"priority\":\"EILIG\"}");
    assertThat(patched.statusCode()).isEqualTo(200);
    assertThat(patched.body()).contains("EILIG");
    assertThat(send("PATCH", "/orders/" + id, "{\"quantity\":3}").statusCode()).as("not in shortage").isEqualTo(409);
    assertThat(send("PATCH", "/orders/" + id, "{\"quantity\":101}").statusCode()).isEqualTo(400);
    assertThat(send("POST", "/orders/" + id + "/cancel", null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/orders/" + id, null).body()).contains("CANCELLED");
  }

  @Test
  void cancellingACompletedOrderIs409() throws Exception {
    String id = createdId(send("POST", "/orders", "{\"productId\":\"FP12\",\"quantity\":1,\"priority\":\"NORMAL\",\"dueInMinutes\":60}"));
    for (int step = 0; step < 10 && !send("GET", "/orders/" + id, null).body().contains("COMPLETED"); step++) {
      for (FakeTransport t : transports.open()) if (id.equals(t.request().orderId())) transports.deliver(t.id());
      for (var job : machines.jobs()) {
        if (job.orderId().equals(id)) feedback.jobFinished(job.machineId(), id, job.stepSeq(), job.quantity(), 0);
      }
    }
    assertThat(send("GET", "/orders/" + id, null).body()).contains("\"status\":\"COMPLETED\"");

    var r = send("POST", "/orders/" + id + "/cancel", null);
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
  }

  @Test
  void repairingARunningMachineIs409() throws Exception {
    feedback.statusChanged("CNC-01", MachineStatus.IDLE, MachineStatus.RUNNING);
    assertThat(send("POST", "/machines/CNC-01/repair", null).statusCode()).isEqualTo(409);
    assertThat(send("POST", "/machines/CNC-01/stop", null).statusCode()).isEqualTo(204);
    assertThat(send("POST", "/machines/CNC-01/fault", "{\"faultTypeId\":\"TOOL\"}").statusCode()).isEqualTo(204);
    assertThat(send("POST", "/machines/CNC-01/fault", null).statusCode()).isEqualTo(204);
    assertThat(send("POST", "/machines/XX-01/start", null).statusCode()).isEqualTo(404);
    assertThat(machines.commands()).endsWith("stop CNC-01", "fault CNC-01 TOOL", "fault CNC-01 null");
    var list = send("GET", "/machines", null);
    assertThat(list.body()).contains("\"id\":\"CNC-01\"").contains("\"status\":\"RUNNING\"").contains("Fräszentrum 1");
  }

  @Test
  void productsComeWithRoutingAndBillOfMaterial() throws Exception {
    var r = send("GET", "/products", null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"id\":\"GH200\"").contains("\"routing\"").contains("MONTAGE").contains("KIT-GH200");
  }

  @Test
  void dispatchRuleCanBeChanged() throws Exception {
    assertThat(send("PUT", "/schedule/rule", "{\"rule\":\"FIFO\"}").statusCode()).isEqualTo(204);
    assertThat(send("GET", "/schedule", null).body()).contains("\"rule\":\"FIFO\"");
    assertThat(send("PUT", "/schedule/rule", "{\"rule\":null}").statusCode()).isEqualTo(400);
    send("PUT", "/schedule/rule", "{\"rule\":\"PRIORITY\"}");
  }

  @Test
  void analyticsWindowMustBeOneMinuteToOneDay() throws Exception {
    assertThat(send("GET", "/analytics?windowMinutes=0", null).statusCode()).isEqualTo(400);
    assertThat(send("GET", "/analytics?windowMinutes=1441", null).statusCode()).isEqualTo(400);
    var ok = send("GET", "/analytics?windowMinutes=60", null);
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.body()).contains("throughputPerHour").contains("\"QC-01\"").contains("oee");
    assertThat(send("GET", "/analytics", null).statusCode()).isEqualTo(200);
  }

  @Test
  void openApiDescribesTheOrderEndpoints() throws Exception {
    var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.body()).contains("/api/v1/orders").contains("/api/v1/machines/{id}/repair");
  }
}

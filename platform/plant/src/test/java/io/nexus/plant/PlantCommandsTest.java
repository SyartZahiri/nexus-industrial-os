package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.json.JsonReader;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.contracts.state.OrderStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlantCommandsTest {
  final Plant plant = Plant.inMemory(1, Scenario1h.rules());

  @SuppressWarnings("unchecked")
  Map<String, Object> send(String json) {
    return (Map<String, Object>) JsonReader.parse(PlantCommands.apply(plant, json));
  }

  @Test
  void createChangeAndCancelAnOrder() {
    var created = send("{\"command\":\"createOrder\",\"productId\":\"FP12\",\"quantity\":3,\"priority\":\"HOCH\",\"dueInMinutes\":90}");
    assertThat(created).containsEntry("ok", true);
    String id = (String) ((Map<String, Object>) created.get("result")).get("id");
    assertThat(send("{\"command\":\"changeOrder\",\"orderId\":\"" + id + "\",\"priority\":\"EILIG\"}")).containsEntry("ok", true);
    assertThat(send("{\"command\":\"cancelOrder\",\"orderId\":\"" + id + "\"}")).containsEntry("ok", true);
    assertThat(plant.smartFactory().order(id).status()).isEqualTo(OrderStatus.CANCELLED);
  }

  @Test
  void machineAgvRouteStockAndRuleCommandsReachThePlant() {
    assertThat(send("{\"command\":\"machineFault\",\"machineId\":\"CNC-02\",\"faultTypeId\":\"COMM\"}")).containsEntry("ok", true);
    assertThat(plant.machines().views().stream().filter(m -> m.id().equals("CNC-02")).findFirst().orElseThrow().status())
        .isEqualTo(MachineStatus.FAULT);
    assertThat(send("{\"command\":\"machineRepair\",\"machineId\":\"CNC-02\"}")).containsEntry("ok", true);
    assertThat(send("{\"command\":\"machineConfigure\",\"machineId\":\"CNC-01\",\"override\":120,\"coolant\":false}"))
        .containsEntry("ok", true);
    assertThat(send("{\"command\":\"agvDisable\",\"agvId\":\"AGV-03\"}")).containsEntry("ok", true);
    assertThat(send("{\"command\":\"agvEnable\",\"agvId\":\"AGV-03\"}")).containsEntry("ok", true);
    assertThat(send("{\"command\":\"agvCharge\",\"agvId\":\"AGV-03\"}")).containsEntry("ok", true);
    String edge = io.nexus.flowpilot.master.FlowPilotMasterData.edges().get(0).id();
    assertThat(send("{\"command\":\"edgeBlock\",\"edgeId\":\"" + edge + "\"}")).containsEntry("ok", true);
    assertThat(send("{\"command\":\"edgeUnblock\",\"edgeId\":\"" + edge + "\"}")).containsEntry("ok", true);
    assertThat(send("{\"command\":\"stockReceipt\",\"materialId\":\"RM-AW50\",\"locationId\":\"RAW-B\",\"quantity\":2}"))
        .containsEntry("ok", true);
    assertThat(send("{\"command\":\"setRules\",\"randomFaults\":false,\"maxOpenOrders\":3}")).containsEntry("ok", true);
    assertThat(plant.rules().machines().randomFaults()).isFalse();
    assertThat(plant.rules().maxOpenOrders()).isEqualTo(3);
    assertThat(send("{\"command\":\"setDispatchRule\",\"rule\":\"EDD\"}")).containsEntry("ok", true);
  }

  /** Numbers that do not fit are refused, never cut to something else. */
  @Test
  void numbersOutOfRangeAreRefused() {
    assertThat(send("{\"command\":\"createOrder\",\"productId\":\"FP12\",\"quantity\":4294967301,\"priority\":\"NORMAL\",\"dueInMinutes\":60}"))
        .containsEntry("status", 400L);
    assertThat(send("{\"command\":\"createOrder\",\"productId\":\"FP12\",\"quantity\":3,\"priority\":\"NORMAL\",\"dueInMinutes\":9000000000000000}"))
        .containsEntry("status", 400L);
    assertThat(send("{\"command\":\"machineConfigure\",\"machineId\":\"CNC-01\",\"override\":4294967396,\"coolant\":true}"))
        .containsEntry("status", 400L);
    assertThat(send("{\"command\":\"setRules\",\"autoOrderIntervalMinutes\":9000000000000000}")).containsEntry("status", 400L);
    assertThat(plant.smartFactory().orders()).isEmpty();
  }

  @Test
  void deeplyNestedJsonIsRefusedNotThrown() {
    assertThat(send("[".repeat(10_000))).containsEntry("status", 400L);
  }

  /** A refused command leaves the plant exactly as it was. */
  @Test
  void refusedCommandChangesNothing() {
    plant.advance(60_000);
    String before = PlantJson.frame(plant.frame());
    int events = plant.events().size();
    assertThat(send("{\"command\":\"machineRepair\",\"machineId\":\"CNC-01\"}")).containsEntry("status", 409L);
    assertThat(send("{\"command\":\"createOrder\",\"productId\":\"NOPE\",\"quantity\":3,\"priority\":\"NORMAL\",\"dueInMinutes\":60}"))
        .containsEntry("status", 404L);
    assertThat(PlantJson.frame(plant.frame())).isEqualTo(before);
    assertThat(plant.events()).hasSize(events);
  }

  @Test
  void unexpectedErrorsNameTheirClassButNotTheirText() {
    var answer = (Map<String, Object>) JsonReader.parse(PlantCommands.apply(null, "{\"command\":\"cancelOrder\",\"orderId\":\"PO-1\"}"));
    assertThat(answer).containsEntry("status", 500L);
    assertThat((String) answer.get("message")).contains("NullPointerException");
  }

  @Test
  void errorsHaveTheServersStatusCodes() {
    assertThat(send("{\"command\":\"explode\"}")).containsEntry("status", 400L);
    assertThat(send("{nope")).containsEntry("status", 400L);
    assertThat(send("[1,2]")).containsEntry("status", 400L);
    assertThat(send("{\"command\":\"createOrder\",\"productId\":\"FP12\",\"quantity\":0,\"priority\":\"NORMAL\",\"dueInMinutes\":60}"))
        .containsEntry("status", 400L);
    assertThat(send("{\"command\":\"createOrder\",\"productId\":\"FP12\",\"quantity\":\"drei\",\"priority\":\"NORMAL\",\"dueInMinutes\":60}"))
        .containsEntry("status", 400L);
    assertThat(send("{\"command\":\"machineStart\",\"machineId\":\"CNC-99\"}")).containsEntry("status", 404L);
    assertThat(send("{\"command\":\"machineRepair\",\"machineId\":\"CNC-01\"}")).containsEntry("status", 409L);
    assertThat(send("{\"command\":\"setDispatchRule\",\"rule\":\"RANDOM\"}")).containsEntry("status", 400L);
  }
}

package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.json.JsonReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlantQueriesTest {
  @SuppressWarnings("unchecked")
  private static Map<String, Object> answer(Plant plant, String json) {
    return (Map<String, Object>) JsonReader.parse(PlantQueries.apply(plant, json));
  }

  @Test
  @SuppressWarnings("unchecked")
  void kpisCoverEveryMachineOfTheWindow() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    plant.advance(30 * 60_000);
    var a = answer(plant, "{\"query\":\"kpis\",\"windowMinutes\":30}");
    assertThat(a).containsEntry("ok", true);
    var result = (Map<String, Object>) a.get("result");
    assertThat(result).containsKeys("throughputPerHour", "onTimeRate", "bottleneckMachineId");
    assertThat((Map<String, Object>) result.get("machines")).containsOnlyKeys("CNC-01", "CNC-02", "CNC-03", "CNC-04",
        "QC-01", "ROB-01", "ROB-02");
    assertThat(result).containsEntry("toMs", 1_800_000L).containsEntry("fromMs", 0L);
  }

  @Test
  void rulesAreTheCurrentOnes() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(PlantQueries.apply(plant, "{\"query\":\"rules\"}"))
        .contains("\"autoOrderIntervalMinutes\":6").contains("\"dispatchRule\":\"PRIORITY\"");
  }

  @Test
  void aBadQueryIsAnsweredLikeABadCommand() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(answer(plant, "{\"query\":\"kpis\",\"windowMinutes\":0}")).containsEntry("ok", false)
        .containsEntry("status", 400L);
    assertThat(answer(plant, "{\"query\":\"geheim\"}")).containsEntry("status", 400L);
    assertThat(answer(plant, "kein json")).containsEntry("status", 400L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void productsCarryRoutingAndBillOfMaterial() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    var a = answer(plant, "{\"query\":\"products\"}");
    assertThat(a).containsEntry("ok", true);
    var products = (java.util.List<Map<String, Object>>) a.get("result");
    assertThat(products).extracting(p -> p.get("id")).contains("GH200", "AW50", "FP12", "MT7");
    var gh = products.stream().filter(p -> p.get("id").equals("GH200")).findFirst().orElseThrow();
    assertThat((java.util.List<?>) gh.get("routing")).isNotEmpty();
    assertThat(gh).containsKeys("name", "shortName", "finishedMaterialId", "bom");
  }

  @Test
  @SuppressWarnings("unchecked")
  void scheduleNamesRuleOpsAndBottleneck() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    plant.advance(20 * 60_000);
    var result = (Map<String, Object>) answer(plant, "{\"query\":\"schedule\"}").get("result");
    assertThat(result).containsKeys("rule", "ops", "unplannable", "lateOrders", "utilization", "bottleneckMachineId");
    assertThat(result).containsEntry("rule", "PRIORITY");
  }

  @Test
  @SuppressWarnings("unchecked")
  void anOrderComesWithItsOperationsAndMaterials() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    plant.advance(20 * 60_000);
    String id = plant.smartFactory().orders().get(0).id();
    var result = (Map<String, Object>) answer(plant, "{\"query\":\"order\",\"orderId\":\"" + id + "\"}").get("result");
    assertThat((Map<String, Object>) result.get("order")).containsEntry("id", id);
    assertThat((java.util.List<?>) result.get("operations")).isNotEmpty();
    assertThat(result).containsKey("materials");
  }

  @Test
  void anUnknownOrderIsNotFound() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(answer(plant, "{\"query\":\"order\",\"orderId\":\"PO-9999\"}")).containsEntry("ok", false)
        .containsEntry("status", 404L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void warehouseListsMaterialsAndLocations() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    var result = (Map<String, Object>) answer(plant, "{\"query\":\"warehouse\"}").get("result");
    assertThat((java.util.List<?>) result.get("materials")).isNotEmpty();
    assertThat((java.util.List<Map<String, Object>>) result.get("locations")).allSatisfy(l ->
        assertThat(l).containsKeys("id", "name", "zone", "capacity"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void networkShowsABlockedEdge() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    var edges = (java.util.List<Map<String, Object>>) ((Map<String, Object>) answer(plant,
        "{\"query\":\"network\"}").get("result")).get("edges");
    String first = (String) edges.get(0).get("id");
    PlantCommands.apply(plant, "{\"command\":\"edgeBlock\",\"edgeId\":\"" + first + "\"}");
    var after = (java.util.List<Map<String, Object>>) ((Map<String, Object>) answer(plant,
        "{\"query\":\"network\"}").get("result")).get("edges");
    assertThat(after.get(0)).containsEntry("id", first).containsEntry("blocked", true);
    assertThat(after.get(1)).containsEntry("blocked", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void seriesNeverStartsBeforeTheRun() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    plant.advance(25 * 60_000);
    var buckets = (java.util.List<Map<String, Object>>) answer(plant,
        "{\"query\":\"kpiSeries\",\"bucketMinutes\":10,\"buckets\":12}").get("result");
    assertThat(buckets).hasSize(3);
    assertThat(buckets.get(0)).containsEntry("fromMs", 0L).containsEntry("toMs", 300_000L);
    assertThat(buckets.get(2)).containsEntry("fromMs", 900_000L).containsEntry("toMs", 1_500_000L);
    assertThat(answer(plant, "{\"query\":\"kpiSeries\",\"bucketMinutes\":0}")).containsEntry("status", 400L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void routemindListsDeliveriesToursVehiclesPlansAndKpis() {
    Plant p = Plant.inMemory(4711, PlantRules.DEFAULT);
    p.advance(2 * 3_600_000L);
    var answer = (Map<String, Object>) JsonReader.parse(PlantQueries.apply(p, "{\"query\":\"routemind\"}"));
    assertThat(answer).containsEntry("ok", true);
    var r = (Map<String, Object>) answer.get("result");
    assertThat((String) r.get("attribution")).contains("OpenStreetMap");
    assertThat((List<Object>) r.get("vehicles")).hasSize(4);
    assertThat((List<Map<String, Object>>) r.get("deliveries")).isNotEmpty()
        .allSatisfy(d -> assertThat(d).containsKeys("id", "orderId", "customerId", "customerName", "status",
            "earliestMs", "latestMs", "plannedArrivalMs", "deliveredAtMs", "weightKg", "pallets"));
    assertThat((Map<String, Object>) r.get("kpis")).containsKeys("activeTours", "utilization", "delivered", "onTime",
        "avgDelayMinutes", "failed", "open", "km", "cost");
    assertThat((List<Object>) r.get("plans")).isNotEmpty();
  }
}

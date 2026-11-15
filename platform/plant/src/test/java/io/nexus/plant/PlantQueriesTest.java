package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.json.JsonReader;
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
}

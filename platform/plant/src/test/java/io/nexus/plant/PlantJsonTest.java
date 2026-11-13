package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.json.JsonReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlantJsonTest {
  @Test
  @SuppressWarnings("unchecked")
  void frameIsValidJsonWithTheWholePlant() {
    Plant plant = Plant.inMemory(4711, PlantRules.DEFAULT);
    plant.advance(600_000);
    var frame = (Map<String, Object>) JsonReader.parse(PlantJson.frame(plant.frame()));
    assertThat(frame).containsEntry("simTimeMs", 600_000L);
    assertThat((List<Object>) frame.get("machines")).hasSize(7);
    assertThat((List<Object>) frame.get("agvs")).hasSize(6);
    assertThat((List<Object>) frame.get("orders")).isNotEmpty();
    assertThat((List<Object>) frame.get("stock")).isNotEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyEventOfARealRunHasAJsonForm() {
    Plant plant = Plant.inMemory(4711, PlantRules.DEFAULT);
    plant.advance(2 * 3_600_000L);
    var events = (List<Object>) JsonReader.parse(PlantJson.events(plant.events()));
    assertThat(events).hasSameSizeAs(plant.events());
    var first = (Map<String, Object>) events.get(0);
    assertThat(first).containsKeys("eventId", "simTimeMs", "type", "payloadType", "payload");
  }

  @Test
  void scenarioTextIsTheSameForTheSameSeed() {
    String a = Scenario1h.run(Plant.inMemory(4711, Scenario1h.rules()));
    String b = Scenario1h.run(Plant.inMemory(4711, Scenario1h.rules()));
    assertThat(a).isEqualTo(b).contains("\"type\":\"order.completed\"").contains("\"machineId\":\"CNC-01\"");
  }
}

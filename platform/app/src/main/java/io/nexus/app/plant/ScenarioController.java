package io.nexus.app.plant;

import io.nexus.contracts.json.JsonWriter;
import io.nexus.plant.Plant;
import io.nexus.plant.PlantCommands;
import io.nexus.plant.PlantQueries;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Scenario Lab (Spec 12, 15): the core's scenarios, the same logic the browser runs in its worker. */
@RestController
@RequestMapping("/api/v1/scenarios")
class ScenarioController {
  private final Plant plant;

  ScenarioController(Plant plant) {
    this.plant = plant;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> list() {
    return SimController.answer(PlantQueries.apply(plant, "{\"query\":\"scenarios\"}"));
  }

  @PostMapping(value = "/{id}/run", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> run(@PathVariable String id) {
    String json = new JsonWriter().beginObject().name("command").value("runScenario").name("scenario").value(id)
        .endObject().toString();
    return SimController.answer(PlantCommands.apply(plant, json));
  }
}

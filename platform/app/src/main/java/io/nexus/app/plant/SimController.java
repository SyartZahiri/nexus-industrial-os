package io.nexus.app.plant;

import io.nexus.app.event.SimRun;
import io.nexus.contracts.json.JsonReader;
import io.nexus.plant.Plant;
import io.nexus.plant.PlantCommands;
import io.nexus.plant.PlantJson;
import io.nexus.plant.PlantQueries;
import io.nexus.plant.PlantRules;
import io.nexus.sim.SimRules;
import io.nexus.smartfactory.planning.DispatchRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Control of the running plant: clock, speed, reset, rules, and the current frame. */
@RestController
@RequestMapping("/api/v1/sim")
class SimController {
  record RulesView(boolean randomFaults, boolean autoRepair, boolean autoMaintenance, double maintenanceThreshold,
                   boolean autoOrders, long autoOrderIntervalMinutes, int maxOpenOrders, boolean shipping,
                   DispatchRule dispatchRule) {
    static RulesView of(PlantRules r) {
      SimRules m = r.machines();
      return new RulesView(m.randomFaults(), m.autoRepair(), m.autoMaintenance(), m.maintenanceThreshold(),
          r.autoOrders(), r.autoOrderIntervalMs() / 60_000, r.maxOpenOrders(), r.shipping(), r.dispatchRule());
    }
  }

  record SimState(long simTimeMs, boolean running, double speed, long seed, long runId, RulesView rules) {}

  record SpeedInput(@NotNull @DecimalMin("0.25") @DecimalMax("25") Double speed) {}

  record ResetInput(Long seed) {}

  record RulesInput(@NotNull Boolean randomFaults, @NotNull Boolean autoRepair, @NotNull Boolean autoMaintenance,
                    @NotNull @DecimalMin("0.01") @DecimalMax("1.0") Double maintenanceThreshold,
                    @NotNull Boolean autoOrders, @NotNull @Min(1) @Max(525_600) Long autoOrderIntervalMinutes,
                    @NotNull @Min(1) @Max(50) Integer maxOpenOrders, @NotNull Boolean shipping,
                    @NotNull DispatchRule dispatchRule) {}

  private final Plant plant;
  private final SimulationLoop loop;
  private final PlantReset reset;
  private final SimRun run;

  SimController(Plant plant, SimulationLoop loop, PlantReset reset, SimRun run) {
    this.plant = plant;
    this.loop = loop;
    this.reset = reset;
    this.run = run;
  }

  @GetMapping
  SimState state() {
    return new SimState(plant.nowMs(), loop.running(), loop.speed(), run.seed(), run.id(), RulesView.of(plant.rules()));
  }

  @PostMapping("/play")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void play() {
    loop.play();
  }

  @PostMapping("/pause")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void pause() {
    loop.pause();
  }

  /** A fresh run; without a seed the current one is kept. A running loop keeps running. */
  @PostMapping("/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reset(@RequestBody(required = false) ResetInput input) {
    reset.reset(input == null || input.seed() == null ? run.seed() : input.seed());
  }

  @PutMapping("/speed")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void speed(@Valid @RequestBody SpeedInput input) {
    loop.setSpeed(input.speed());
  }

  @PutMapping("/rules")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void rules(@Valid @RequestBody RulesInput r) {
    plant.setRules(new PlantRules(
        new SimRules(r.randomFaults(), r.autoRepair(), r.autoMaintenance(), r.maintenanceThreshold()),
        r.autoOrders(), r.autoOrderIntervalMinutes() * 60_000, r.maxOpenOrders(), r.shipping(), r.dispatchRule()));
  }

  /** The same JSON the browser runtime produces, so the user interface reads both the same way. */
  @GetMapping(value = "/frame", produces = MediaType.APPLICATION_JSON_VALUE)
  String frame() {
    return PlantJson.frame(plant.frame());
  }

  /**
   * A command in the core's JSON (see PlantCommands): the same format and checks as in the browser. Only
   * application/json: a foreign page cannot send that without a CORS preflight, which this server refuses.
   */
  @PostMapping(value = "/commands", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> command(@RequestBody String json) {
    return answer(PlantCommands.apply(plant, json));
  }

  /** A read-only question in the core's JSON (see PlantQueries). */
  @PostMapping(value = "/queries", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> query(@RequestBody String json) {
    return answer(PlantQueries.apply(plant, json));
  }

  /** The core's answer unchanged; its status field, if any, becomes the HTTP status. */
  private static ResponseEntity<String> answer(String json) {
    Object status = ((Map<?, ?>) JsonReader.parse(json)).get("status");
    return ResponseEntity.status(status instanceof Long s ? s.intValue() : 200)
        .contentType(MediaType.APPLICATION_JSON).body(json);
  }
}

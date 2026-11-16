package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.json.JsonReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenariosTest {
  static final long MIN = 60_000;

  @SuppressWarnings("unchecked")
  static Map<String, Object> answer(String json) {
    return (Map<String, Object>) JsonReader.parse(json);
  }

  static Map<String, Object> start(Plant p, String id) {
    return answer(PlantCommands.apply(p, "{\"command\":\"runScenario\",\"scenario\":\"" + id + "\"}"));
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> runs(Plant p) {
    var result = (Map<String, Object>) answer(PlantQueries.apply(p, "{\"query\":\"scenarios\"}")).get("result");
    return (List<Map<String, Object>>) result.get("runs");
  }

  /** Advances minute by minute until the newest run has ended or limitMs have passed. */
  static Map<String, Object> untilDone(Plant p, long limitMs) {
    for (long t = 0; t < limitMs; t += MIN) {
      p.advance(MIN);
      String state = (String) runs(p).get(0).get("state");
      if (state.equals("PASSED") || state.equals("FAILED")) break;
    }
    return runs(p).get(0);
  }

  @SuppressWarnings("unchecked")
  static List<String> texts(Map<String, Object> run) {
    return ((List<Map<String, Object>>) run.get("steps")).stream().map(s -> (String) s.get("text")).toList();
  }

  static List<NexusEvent> ofType(Plant p, String type) {
    return p.events().stream().filter(e -> e.type().equals(type)).toList();
  }

  @Test
  @SuppressWarnings("unchecked")
  void listsTheTenScenarios() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    var result = (Map<String, Object>) answer(PlantQueries.apply(p, "{\"query\":\"scenarios\"}")).get("result");
    assertThat((List<Map<String, Object>>) result.get("definitions")).extracting(d -> d.get("id"))
        .containsExactly("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
    assertThat(runs(p)).isEmpty();
  }

  @Test
  void aFullRunEndsWithThePutaway() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "E")).containsEntry("ok", true);
    var run = untilDone(p, 6 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    String orderId = (String) run.get("orderId");
    assertThat(texts(run)).anyMatch(t -> t.contains("freigegeben")).anyMatch(t -> t.startsWith("Schritt 1"));
    assertThat(ofType(p, EventTypes.SCENARIO_STARTED)).singleElement().extracting(NexusEvent::correlationId).isEqualTo(orderId);
    assertThat(ofType(p, EventTypes.SCENARIO_FINISHED)).singleElement().extracting(NexusEvent::correlationId).isEqualTo(orderId);
  }

  @Test
  void aSecondScenarioWaitsForTheFirst() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "E")).containsEntry("ok", true);
    var second = start(p, "D");
    assertThat(second).containsEntry("ok", false).containsEntry("status", 409L);
    assertThat((String) second.get("message")).contains("läuft noch");
    assertThat(start(p, "Z")).containsEntry("status", 404L); // unknown stays unknown while another runs
    assertThat(runs(p)).hasSize(1);
  }

  @Test
  void anUnknownScenarioIsNotFound() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "Z")).containsEntry("ok", false).containsEntry("status", 404L);
  }

  @Test
  void aResetEndsTheScenario() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    start(p, "E");
    p.advance(MIN);
    p.reset(7);
    assertThat(runs(p)).isEmpty();
    p.advance(10 * MIN);
    assertThat(ofType(p, EventTypes.SCENARIO_FINISHED)).isEmpty();
    assertThat(start(p, "E")).containsEntry("ok", true); // nothing blocks a new run
  }

  @Test
  void aMachineFailureMovesTheStepToAnotherMachine() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    p.advance(5 * MIN);
    assertThat(start(p, "A")).containsEntry("ok", true);
    var run = untilDone(p, 30 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).last().asString().contains("umgeplant");
  }

  @Test
  void aMachineFailureRightAfterTheStartFindsAnAlternative() {
    Plant p = Plant.inMemory(4711, PlantRules.DEFAULT); // the browser's default seed, scenario started at once
    p.advance(5_000);
    assertThat(start(p, "A")).containsEntry("ok", true);
    var run = untilDone(p, 30 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).last().asString().contains("Ausweichmaschine");
  }

  @Test
  void anAgvFailureHandsTheLoadToAnotherAgv() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    p.advance(5 * MIN);
    assertThat(start(p, "B")).containsEntry("ok", true);
    var run = untilDone(p, 30 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("Störung NAV"));
  }

  @Test
  void aScenarioThatCannotTriggerFailsWithItsReason() {
    Plant p = Plant.inMemory(7, Scenario1h.rules()); // no automatic orders: no CNC will ever run
    assertThat(start(p, "A")).containsEntry("ok", true);
    var run = untilDone(p, 31 * MIN);
    assertThat(run).containsEntry("state", "FAILED");
    assertThat(texts(run)).last().asString().contains("Keine CNC");
  }

  @Test
  void aShortageStopsTheOrderBeforeReservation() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "C")).containsEntry("ok", true);
    var run = untilDone(p, 30 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(p.smartFactory().order((String) run.get("orderId")).status().name()).isIn("SHORTAGE", "MATERIAL_PENDING", "RELEASED");
    assertThat(texts(run)).anyMatch(t -> t.contains("Wareneingang"));
  }

  @Test
  void shortageNeverBooksBelowZero() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    // empty the first material of the first product completely before the scenario
    String product = io.nexus.smartfactory.master.SmartFactoryMasterData.products().get(0).id();
    String material = p.smartFactory().catalog().demandFor(product, 1).get(0).materialId();
    for (var line : p.flowPilot().stock()) {
      if (line.materialId().equals(material) && line.available() > 0) p.flowPilot().issue(material, line.locationId(), line.available());
    }
    assertThat(start(p, "C")).containsEntry("ok", true);
    assertThat(p.flowPilot().stock()).allSatisfy(l -> assertThat(l.onHand()).isNotNegative());
    assertThat(untilDone(p, 30 * MIN)).containsEntry("state", "PASSED");
  }

  @Test
  void anExpressOrderShiftsLaterOrders() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    p.advance(20 * MIN);
    assertThat(start(p, "D")).containsEntry("ok", true);
    var run = untilDone(p, 5 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.endsWith("min später")); // at least one order really moved
    assertThat(texts(run)).last().asString().endsWith("Aufträge verschoben");
  }

  @Test
  void anExpressOrderAloneShiftsNothing() {
    Plant p = Plant.inMemory(7, Scenario1h.rules()); // no other orders
    assertThat(start(p, "D")).containsEntry("ok", true);
    var run = untilDone(p, 5 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).last().isEqualTo("Kein anderer Auftrag verschoben");
  }


  @Test
  @SuppressWarnings("unchecked")
  void aPromisesOnlyWhatItChecks() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    var result = (Map<String, Object>) answer(PlantQueries.apply(p, "{\"query\":\"scenarios\"}")).get("result");
    var a = ((List<Map<String, Object>>) result.get("definitions")).get(0);
    assertThat((String) a.get("expected")).doesNotContain("Transporte"); // A checks the plan, not rerouted transports
  }

  @Test
  void aFailingInterventionIsRecordedAndBlocksNothing() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    Scenarios sc = p.scenarios();
    Scenarios.Run broken = sc.new Run() {
      @Override
      void begin(long now) {
        throw new IllegalStateException("Eingriff gescheitert");
      }

      @Override
      void check(long now) {}

      @Override
      String timeoutReason() {
        return "";
      }
    };
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sc.begin(Scenarios.DEFINITIONS.get(4), broken, 0))
        .hasMessage("Eingriff gescheitert");
    // the attempt stays visible as FAILED with its reason, so a partly applied intervention can be traced …
    assertThat(runs(p)).singleElement().satisfies(r -> {
      assertThat(r).containsEntry("state", "FAILED");
      assertThat(texts(r)).last().asString().contains("Eingriff gescheitert");
    });
    // … and, being finished, it blocks nothing
    assertThat(start(p, "E")).containsEntry("ok", true).extractingByKey("result").asString().contains("SZ-2");
  }

  @Test
  void anErrorInTheInterventionStillEndsTheRun() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    Scenarios sc = p.scenarios();
    Scenarios.Run deep = sc.new Run() {
      @Override
      void begin(long now) {
        throw new StackOverflowError(); // an Error, not a RuntimeException
      }

      @Override
      void check(long now) {}

      @Override
      String timeoutReason() {
        return "";
      }
    };
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sc.begin(Scenarios.DEFINITIONS.get(4), deep, 0))
        .isInstanceOf(StackOverflowError.class);
    assertThat(runs(p)).singleElement().extracting(r -> r.get("state")).isEqualTo("FAILED");
    assertThat(start(p, "E")).containsEntry("ok", true); // nothing left active that would answer 409
  }

  @Test
  void aProductionDelayKeepsTheUnfinishedGoodsOffTheTour() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "F")).containsEntry("ok", true);
    var run = untilDone(p, 6 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("angehalten"));
    assertThat(p.machines().views()).noneSatisfy(m ->
        assertThat(m.status()).isEqualTo(io.nexus.contracts.state.MachineStatus.STOPPED)); // restarted at the end
  }

  @Test
  void aVehicleBreakdownLeadsToANewTour() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "G")).containsEntry("ok", true);
    var run = untilDone(p, 6 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("Panne"));
  }

  @Test
  void anExpressOrderGetsADeliveryPlan() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "H")).containsEntry("ok", true);
    var run = untilDone(p, 3 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("Tour"));
  }

  @Test
  void aShortageLeavesTheDeliveryWithoutDate() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "I")).containsEntry("ok", true);
    var run = untilDone(p, 2 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
  }

  @Test
  void theWholeSupplyChainEndsWithTheDelivery() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "J")).containsEntry("ok", true);
    var run = untilDone(p, 10 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("eingelagert")).anyMatch(t -> t.contains("verladen"))
        .anyMatch(t -> t.contains("zugestellt"));
  }
}

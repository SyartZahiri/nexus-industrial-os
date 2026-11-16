package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.sim.SimRules;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.planning.DispatchRule;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.ProductionOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PlantEndToEndTest {
  static final PlantRules QUIET = new PlantRules(new SimRules(false, true, false, 0.85), false, 360_000, 6, false,
      DispatchRule.PRIORITY);

  Plant plant = Plant.inMemory(4711, QUIET);

  void advanceUntil(BooleanSupplier done, long maxMs, String what) {
    for (long t = 0; t < maxMs && !done.getAsBoolean(); t += 10_000) plant.advance(10_000);
    assertThat(done.getAsBoolean()).as(what).isTrue();
  }

  ProductionOrder order(String id) {
    return plant.smartFactory().order(id);
  }

  boolean completed(List<String> ids) {
    return ids.stream().allMatch(id -> order(id).status() == OrderStatus.COMPLETED);
  }

  List<String> typesOf(String orderId) {
    return plant.events().stream().filter(e -> orderId.equals(e.correlationId())).map(NexusEvent::type).toList();
  }

  int onHand(String materialId) {
    return plant.flowPilot().stock().stream().filter(l -> l.materialId().equals(materialId))
        .mapToInt(StockLine::onHand).sum();
  }

  @Test
  void everyProductRunsFromOrderToFinishedStock() {
    int rawBefore = onHand("RM-GH200");
    int finishedBefore = onHand("FG-GH200");
    List<String> ids = new ArrayList<>();
    for (String product : List.of("GH200", "AW50", "FP12", "MT7")) {
      ids.add(plant.smartFactory().createOrder(new NewOrder(product, 4, Priority.NORMAL, 3 * 3_600_000L)));
    }
    advanceUntil(() -> completed(ids), 2 * 3_600_000L, "all four orders completed");

    for (String id : ids) {
      assertThat(typesOf(id)).as(id).containsSubsequence(EventTypes.ORDER_CREATED, EventTypes.MATERIAL_RESERVED,
          EventTypes.TRANSPORT_CREATED, EventTypes.TRANSPORT_DELIVERED, EventTypes.ORDER_RELEASED,
          EventTypes.OPERATION_STARTED, EventTypes.OPERATION_COMPLETED, EventTypes.ORDER_COMPLETED);
    }
    ProductionOrder gh = order(ids.get(0));
    assertThat(gh.good() + gh.scrapped()).isEqualTo(4);
    assertThat(onHand("FG-GH200")).isEqualTo(finishedBefore + gh.good());
    assertThat(onHand("RM-GH200")).isEqualTo(rawBefore - 4);
  }

  @Test
  void machineFaultDuringAnOrderIsReplannedAndTheOrderCompletes() {
    String id = plant.smartFactory().createOrder(new NewOrder("GH200", 4, Priority.NORMAL, 3 * 3_600_000L));
    plant.advance(Plant.STEP_MS);
    String planned = plant.smartFactory().operations(id).get(0).machineId();
    plant.smartFactory().injectFault(planned, "SERVO");
    advanceUntil(() -> order(id).status() == OrderStatus.COMPLETED, 3 * 3_600_000L, "order completed");
    assertThat(typesOf(id)).contains(EventTypes.ORDER_RESCHEDULED);
    Operation milling = plant.smartFactory().operations(id).get(0);
    assertThat(milling.machineId()).isNotEqualTo(planned);
  }

  @Test
  void agvFaultDuringASupplyTransportIsRescuedAndTheOrderCompletes() {
    String id = plant.smartFactory().createOrder(new NewOrder("FP12", 3, Priority.NORMAL, 3 * 3_600_000L));
    TransportOrder[] loaded = new TransportOrder[1];
    advanceUntilStep(() -> {
      for (TransportOrder t : plant.flowPilot().openTransports()) {
        if (id.equals(t.orderId()) && t.status() == TransportStatus.IN_TRANSIT) loaded[0] = t;
      }
      return loaded[0] != null;
    }, 1_800_000, "a supply transport is under way");
    plant.flowPilot().injectFault(loaded[0].agvId(), "NAV");
    advanceUntil(() -> order(id).status() == OrderStatus.COMPLETED, 3 * 3_600_000L, "order completed");
    assertThat(typesOf(id)).contains(EventTypes.TRANSPORT_FAILED, EventTypes.TRANSPORT_REASSIGNED);
  }

  private void advanceUntilStep(BooleanSupplier done, long maxMs, String what) {
    for (long t = 0; t < maxMs && !done.getAsBoolean(); t += Plant.STEP_MS) plant.advance(Plant.STEP_MS);
    assertThat(done.getAsBoolean()).as(what).isTrue();
  }

  @Test
  void fullFinishedStockDelaysButDoesNotBlockAnOrder() {
    plant.flowPilot().receive("FG-FP12", "FG-A", 160 - 21 - 2);       // two places left
    String id = plant.smartFactory().createOrder(new NewOrder("FP12", 5, Priority.NORMAL, 3 * 3_600_000L));
    long[] waitingSince = {-1};
    advanceUntilStep(() -> {
      boolean waiting = plant.flowPilot().openTransports().stream()
          .anyMatch(t -> id.equals(t.orderId()) && t.status() == TransportStatus.UNLOADING);
      if (waiting && waitingSince[0] < 0) waitingSince[0] = plant.nowMs();
      return waiting && plant.nowMs() - waitingSince[0] > 60_000;
    }, 3 * 3_600_000L, "the putaway waits in front of the full warehouse");
    assertThat(order(id).status()).isNotEqualTo(OrderStatus.COMPLETED);

    plant.setRules(new PlantRules(QUIET.machines(), false, 360_000, 6, true, DispatchRule.PRIORITY));
    advanceUntil(() -> order(id).status() == OrderStatus.COMPLETED, 600_000, "completed once shipping makes room");
  }

  @Test
  void autoOrdersAndShippingKeepThePlantBusy() {
    plant = Plant.inMemory(4711, PlantRules.DEFAULT);
    plant.advance(2 * 3_600_000L);
    assertThat(plant.smartFactory().orders()).hasSizeGreaterThan(5);
    assertThat(plant.smartFactory().orders()).anyMatch(o -> o.status() == OrderStatus.COMPLETED);
  }

  private List<String> run(long seed) {
    var p = Plant.inMemory(seed, PlantRules.DEFAULT);
    p.advance(3_600_000);
    return p.events().stream().map(e -> e.simTimeMs() + " " + e.type() + " " + e.correlationId() + " " + e.payload())
        .toList();
  }

  @Test
  void sameSeedSameEvents() {
    List<String> first = run(7);
    assertThat(first).hasSizeGreaterThan(100).isEqualTo(run(7));
    assertThat(run(8)).isNotEqualTo(first);
  }

  /** One failing phase must not stop the others or the clock: the server loop and the web worker keep running. */
  @Test
  void aFailingPhaseIsReportedAndTheStepGoesOn() {
    int[] calls = {0};
    plant.addPhaseForTest("kaputt", () -> {
      calls[0]++;
      throw new IllegalStateException("geheim");
    });
    String id = plant.smartFactory().createOrder(new NewOrder("FP12", 2, Priority.NORMAL, 3 * 3_600_000L));
    advanceUntil(() -> order(id).status() == OrderStatus.COMPLETED, 2 * 3_600_000L, "order completes despite the phase");
    assertThat(calls[0]).isGreaterThan(100);
    assertThat(plant.events()).anySatisfy(e -> {
      assertThat(e.type()).isEqualTo(EventTypes.DECISION);
      assertThat(e.severity()).isEqualTo(io.nexus.contracts.Domain.Severity.ERROR);
      assertThat(e.payload().toString()).contains("kaputt").contains("IllegalStateException").doesNotContain("geheim");
    });
  }

  @Test
  void advanceRejectsNegativeAndHugeDurations() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> plant.advance(-1))
        .isInstanceOf(io.nexus.contracts.DomainException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> plant.advance(Plant.MAX_ADVANCE_MS + 1))
        .isInstanceOf(io.nexus.contracts.DomainException.class);
    assertThat(plant.advance(0)).isZero();
    assertThat(plant.nowMs()).isZero();
  }

  /** The machines and SmartFactory never disagree about what a machine is doing. */
  @Test
  void simulationAndSmartFactoryAgreeOnEveryMachine() {
    plant = Plant.inMemory(9, PlantRules.DEFAULT);
    for (int i = 0; i < 3 * 36_000; i++) {
      plant.advance(Plant.STEP_MS);
      if (i % 10 == 0) PlantLongRunTest.checkMachinesAgree(plant);
    }
  }

  @Test
  void frameShowsTheWholePlant() {
    plant.smartFactory().createOrder(new NewOrder("GH200", 2, Priority.EILIG, 3_600_000));
    plant.advance(120_000);
    PlantFrame f = plant.frame();
    assertThat(f.simTimeMs()).isEqualTo(120_000);
    assertThat(f.machines()).hasSize(7);
    assertThat(f.agvs()).hasSize(6);
    assertThat(f.conveyors()).extracting(c -> c.conveyorId()).containsExactly("CV-03");
    assertThat(f.orders()).singleElement().satisfies(o -> {
      assertThat(o.steps()).isEqualTo(3);
      assertThat(o.priority()).isEqualTo("EILIG");
    });
    assertThat(f.stock()).isNotEmpty();
  }

  @Test
  void productionOrderIsDeliveredToTheCustomer() {
    Plant plant = Plant.inMemory(4711, Scenario1h.rules());
    String id = plant.smartFactory().createOrder(new NewOrder("FP12", 4, Priority.HOCH, 60 * 60_000L));
    for (int minute = 0; minute < 8 * 60; minute++) {
      if (plant.routeMind().deliveryOfOrder(id).status() == DeliveryStatus.DELIVERED) break;
      plant.advance(60_000);
    }
    assertThat(plant.routeMind().deliveryOfOrder(id).status()).isEqualTo(DeliveryStatus.DELIVERED);
    List<String> chain = new ArrayList<>();
    for (NexusEvent e : plant.events()) {
      if (id.equals(e.correlationId()) && e.type().equals(EventTypes.DELIVERY_STATE)) {
        chain.add(((Payloads.DeliveryStateChanged) e.payload()).to().name());
      }
    }
    assertThat(chain).containsSubsequence("AWAITING_GOODS", "READY_TO_LOAD", "LOADED", "IN_TRANSIT", "DELIVERED");
    assertThat(plant.events()).anySatisfy(e -> {
      assertThat(e.type()).isEqualTo(EventTypes.TRANSPORT_DELIVERED);
      var d = (Payloads.TransportDelivered) e.payload();
      assertThat(d.kind()).isEqualTo(TransportKind.SHIPMENT);
      assertThat(d.orderId()).isEqualTo(id);
      assertThat(d.to()).isEqualTo(Place.loading("LZ-1"));
    });
  }

  @Test
  void sameSeedSameDeliveryEvents() {
    List<String> a = new ArrayList<>();
    List<String> b = new ArrayList<>();
    for (List<String> out : List.of(a, b)) {
      Plant plant = Plant.inMemory(99, PlantRules.DEFAULT);
      plant.advance(3 * 3_600_000L);
      for (NexusEvent e : plant.events()) {
        if (e.source().equals("routemind")) out.add(PlantJson.event(e, false));
      }
    }
    assertThat(a).isNotEmpty().isEqualTo(b);
  }
}

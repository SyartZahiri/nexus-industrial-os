package io.nexus.app.plant;

import io.nexus.app.event.SimRun;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.plant.Plant;
import io.nexus.smartfactory.SmartFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts the server plant afresh with a seed: movement tables back to master data, clock, id counters and event run
 * new, every module's memory reset. Afterwards the plant runs exactly like a new one with that seed. Events of
 * earlier runs stay in the event table under their run number.
 */
@Component
public class PlantReset implements ApplicationRunner {
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final IdSource ids;
  private final SimRun run;
  private final SimTime time;
  private final EventBus bus;
  private final FlowPilot flowPilot;
  private final SmartFactory smartFactory;
  private final Plant plant;
  private final SimulationLoop loop;

  PlantReset(JdbcClient jdbc, TransactionTemplate tx, IdSource ids, SimRun run, SimTime time, EventBus bus,
             FlowPilot flowPilot, SmartFactory smartFactory, Plant plant, SimulationLoop loop) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.ids = ids;
    this.run = run;
    this.time = time;
    this.bus = bus;
    this.flowPilot = flowPilot;
    this.smartFactory = smartFactory;
    this.plant = plant;
    this.loop = loop;
  }

  /**
   * At start the tables may still hold the movements of an earlier server process (a real PostgreSQL keeps them);
   * the plant in memory is new. A reset brings both to the same fresh state.
   */
  @Override
  public void run(ApplicationArguments args) {
    reset(run.seed());
  }

  /**
   * Runs under the platform lock, so no simulation step and no command interleaves with it. Tables and the new run
   * commit together. Should a later part fail, memory and tables no longer match: the loop is paused and the failure
   * reported, a further reset repairs it.
   */
  public void reset(long seed) {
    synchronized (bus) {
      try {
        tx.executeWithoutResult(s -> {
          clearMovementTables();
          ids.reset();
          run.restart(seed);
        });
        time.reset();
        bus.reset();
        flowPilot.reset();
        smartFactory.reset();
        plant.reset(seed);
      } catch (RuntimeException | Error e) {
        loop.pause();
        PlantConfig.report(bus, e);
        throw e;
      }
    }
  }

  private void clearMovementTables() {
    for (String sql : new String[] {
        "delete from event_delivery_failure",
        "delete from processed_event",
        "delete from transport_order",
        "delete from reservation",
        "delete from operation_material",
        "delete from operation",
        "delete from production_order",
        "delete from machine_state_interval",
        "delete from maintenance_window",
        "delete from stock",
        "update graph_edge set blocked = false",
        "update machine set status = 'IDLE', last_product_id = null, version = 0"}) {
      jdbc.sql(sql).update();
    }
    for (var s : FlowPilotMasterData.initialStock()) {
      jdbc.sql("insert into stock (material_id, location_id, on_hand) values (:m, :l, :q)")
          .param("m", s.materialId()).param("l", s.locationId()).param("q", s.onHand()).update();
    }
    for (var a : FlowPilotMasterData.agvs()) {
      jdbc.sql("""
          update agv set status = 'IDLE', node_id = :n, battery = 100, enabled = true, version = 0 where id = :id
          """).param("n", a.nodeId()).param("id", a.id()).update();
    }
  }
}

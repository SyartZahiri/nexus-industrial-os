package io.nexus.app.flowpilot;

import io.nexus.flowpilot.master.FlowPilotMasterData;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Puts FlowPilot's tables back to the master data, as a fresh database has them. */
final class FlowPilotTestData {
  private FlowPilotTestData() {}

  static void reset(JdbcClient jdbc) {
    jdbc.sql("delete from transport_order").update();
    jdbc.sql("delete from reservation").update();
    jdbc.sql("delete from stock").update();
    for (var s : FlowPilotMasterData.initialStock()) {
      jdbc.sql("insert into stock (material_id, location_id, on_hand) values (:m, :l, :q)")
          .param("m", s.materialId()).param("l", s.locationId()).param("q", s.onHand()).update();
    }
    for (var a : FlowPilotMasterData.agvs()) {
      jdbc.sql("update agv set status = 'IDLE', node_id = :n, battery = 100, enabled = true, version = 0 where id = :id")
          .param("n", a.nodeId()).param("id", a.id()).update();
    }
    jdbc.sql("update graph_edge set blocked = false").update();
  }
}

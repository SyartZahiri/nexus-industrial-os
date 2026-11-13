package io.nexus.app.flowpilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.flowpilot.master.FlowPilotMasterData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The browser has no database, so FlowPilot's master data also lives in the core. This test keeps both equal.
 * Own context (property below): other tests change stock, the initial stock is only checked on a fresh database.
 */
@SpringBootTest(properties = "nexus.test.context=master-data")
@ActiveProfiles("embedded")
class MasterDataMatchesDatabaseTest {
  @Autowired JdbcClient jdbc;

  private static <T> List<T> sorted(List<T> list, Function<T, String> key) {
    var copy = new ArrayList<>(list);
    copy.sort(Comparator.comparing(key));
    return copy;
  }

  @Test
  void materials() {
    var db = jdbc.sql("select id, name, kind, reorder_point, reorder_qty from material order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.Material(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getInt(4), rs.getInt(5))).list();
    assertThat(sorted(FlowPilotMasterData.materials(), FlowPilotMasterData.Material::id)).isEqualTo(db);
  }

  @Test
  void locations() {
    var db = jdbc.sql("select id, name, zone, dock_node, capacity from storage_location order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.StorageLocation(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getInt(5))).list();
    assertThat(sorted(FlowPilotMasterData.locations(), FlowPilotMasterData.StorageLocation::id)).isEqualTo(db);
  }

  @Test
  void initialStock() {
    var db = jdbc.sql("select material_id, location_id, on_hand from stock order by material_id collate \"C\", location_id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.StockSeed(rs.getString(1), rs.getString(2), rs.getInt(3))).list();
    assertThat(sorted(FlowPilotMasterData.initialStock(), s -> s.materialId() + "\u0000" + s.locationId()))
        .isEqualTo(db);
  }

  @Test
  void nodes() {
    var db = jdbc.sql("select id, x, z, dock from graph_node order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.Node(rs.getString(1), rs.getDouble(2), rs.getDouble(3),
            rs.getBoolean(4))).list();
    var core = sorted(FlowPilotMasterData.nodes(), FlowPilotMasterData.Node::id);
    assertThat(core).hasSameSizeAs(db);
    for (int i = 0; i < db.size(); i++) {
      var a = core.get(i);
      var b = db.get(i);
      assertThat(a.id()).isEqualTo(b.id());
      assertThat(a.dock()).as(a.id()).isEqualTo(b.dock());
      assertThat(a.x()).as(a.id()).isCloseTo(b.x(), org.assertj.core.data.Offset.offset(1e-9));
      assertThat(a.z()).as(a.id()).isCloseTo(b.z(), org.assertj.core.data.Offset.offset(1e-9));
    }
  }

  @Test
  void edges() {
    var db = jdbc.sql("select id, from_node, to_node, length from graph_edge order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.Edge(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getDouble(4))).list();
    var core = sorted(FlowPilotMasterData.edges(), FlowPilotMasterData.Edge::id);
    assertThat(core).hasSameSizeAs(db);
    for (int i = 0; i < db.size(); i++) {
      var a = core.get(i);
      var b = db.get(i);
      assertThat(List.of(a.id(), a.from(), a.to())).isEqualTo(List.of(b.id(), b.from(), b.to()));
      assertThat(a.length()).as(a.id()).isCloseTo(b.length(), org.assertj.core.data.Offset.offset(1e-9));
    }
  }

  @Test
  void agvs() {
    var db = jdbc.sql("select id, name, node_id from agv order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.AgvSeed(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    assertThat(sorted(FlowPilotMasterData.agvs(), FlowPilotMasterData.AgvSeed::id)).isEqualTo(db);
  }

  @Test
  void chargers() {
    var db = jdbc.sql("select id, node_id from charger order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.Charger(rs.getString(1), rs.getString(2))).list();
    assertThat(sorted(FlowPilotMasterData.chargers(), FlowPilotMasterData.Charger::id)).isEqualTo(db);
  }

  @Test
  void machineDocks() {
    var db = jdbc.sql("select id, dock_node, out_dock_node from machine order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.MachineDock(rs.getString(1), rs.getString(2), rs.getString(3)))
        .list();
    assertThat(sorted(FlowPilotMasterData.machineDocks(), FlowPilotMasterData.MachineDock::machineId)).isEqualTo(db);
  }

  @Test
  void conveyorDocks() {
    var db = jdbc.sql("select id, dock_node from conveyor order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.ConveyorDock(rs.getString(1), rs.getString(2))).list();
    assertThat(sorted(FlowPilotMasterData.conveyorDocks(), FlowPilotMasterData.ConveyorDock::conveyorId))
        .isEqualTo(db);
  }
}

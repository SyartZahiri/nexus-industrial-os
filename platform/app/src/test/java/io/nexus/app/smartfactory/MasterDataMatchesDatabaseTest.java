package io.nexus.app.smartfactory;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.Domain.ProcessType;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.master.SmartFactoryMasterData.BomItem;
import io.nexus.smartfactory.master.SmartFactoryMasterData.Machine;
import io.nexus.smartfactory.master.SmartFactoryMasterData.Product;
import io.nexus.smartfactory.master.SmartFactoryMasterData.RoutingStep;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** The browser runtime has no database: its master data is Java code and must equal the seeded tables. */
@SpringBootTest
@ActiveProfiles("embedded")
class MasterDataMatchesDatabaseTest {
  @Autowired JdbcClient jdbc;

  @Test
  void productsMatch() {
    List<Product> db = jdbc.sql("select id, name, short_name, color, finished_material_id from product order by id")
        .query((rs, i) -> new Product(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
        .list();
    assertThat(SmartFactoryMasterData.products()).containsExactlyInAnyOrderElementsOf(db);
  }

  @Test
  void routingMatches() {
    List<RoutingStep> db = jdbc.sql("""
        select product_id, seq, process, base_time_s, preferred_machine_id from routing_step order by product_id, seq
        """).query((rs, i) -> new RoutingStep(rs.getString(1), rs.getInt(2), ProcessType.valueOf(rs.getString(3)),
            rs.getInt(4), rs.getString(5))).list();
    assertThat(SmartFactoryMasterData.routing()).containsExactlyInAnyOrderElementsOf(db);
  }

  @Test
  void billsOfMaterialMatch() {
    List<BomItem> db = jdbc.sql("select product_id, seq, material_id, qty from bom_item")
        .query((rs, i) -> new BomItem(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4))).list();
    assertThat(SmartFactoryMasterData.bom()).containsExactlyInAnyOrderElementsOf(db);
  }

  /** Docks are FlowPilot's business and checked there; only whether a machine hands over to a conveyor matters here. */
  @Test
  void machinesMatch() {
    List<Machine> db = jdbc.sql("""
        select id, name, kind, model, capabilities, pos_x, pos_z, out_dock_node is null as to_conveyor from machine
        """).query((rs, i) -> new Machine(rs.getString(1), rs.getString(2), MachineKind.valueOf(rs.getString(3)),
            rs.getString(4), Arrays.stream((String[]) rs.getArray(5).getArray()).map(ProcessType::valueOf).toList(),
            rs.getDouble(6), rs.getDouble(7), rs.getBoolean(8))).list();
    assertThat(SmartFactoryMasterData.machines()).containsExactlyInAnyOrderElementsOf(db);
  }
}

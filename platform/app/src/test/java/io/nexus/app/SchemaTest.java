package io.nexus.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional // every test rolls back
class SchemaTest {
  @Autowired JdbcClient jdbc;

  private int count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
  }

  @Test
  void masterDataIsSeeded() {
    assertThat(count("product")).isEqualTo(4);
    assertThat(count("machine")).isEqualTo(7);
    assertThat(count("routing_step")).isEqualTo(11);
    assertThat(count("agv")).isEqualTo(6);
    assertThat(count("material")).isEqualTo(10);
  }

  @Test
  void everyRoutingStepHasACapableMachine() {
    int uncovered = jdbc.sql("""
        select count(*) from routing_step r
        where not exists (select 1 from machine m where r.process = any(m.capabilities))
        """).query(Integer.class).single();
    assertThat(uncovered).isZero();
  }

  @Test
  void pathGraphIsConnectedToEveryDockInUse() {
    int missing = jdbc.sql("""
        select count(*) from (
          select dock_node from machine where dock_node is not null
          union select dock_node from storage_location
          union select node_id from agv
        ) d where not exists (select 1 from graph_node n where n.id = d.dock_node)
        """).query(Integer.class).single();
    assertThat(missing).isZero();
    int deadEnds = jdbc.sql("""
        select count(*) from graph_node n
        where not exists (select 1 from graph_edge e where e.from_node = n.id)
           or not exists (select 1 from graph_edge e where e.to_node = n.id)
        """).query(Integer.class).single();
    assertThat(deadEnds).isZero();
  }

  /** Replenishment tops a material up to at most reorder_point + reorder_qty; that must fit where it is stored. */
  @Test
  void everyLocationCanHoldItsMaterialsAtTheirReplenishmentMaximum() {
    int tooSmall = jdbc.sql("""
        select count(*) from storage_location l
        where l.capacity < (
          select coalesce(sum(greatest(s.on_hand, m.reorder_point + m.reorder_qty)), 0)
          from stock s join material m on m.id = s.material_id where s.location_id = l.id)
        """).query(Integer.class).single();
    assertThat(tooSmall).isZero();
  }

  @Test
  void databaseRejectsReservingMoreThanOnHand() {
    assertThatThrownBy(() -> jdbc.sql(
        "update stock set reserved = on_hand + 1 where material_id = 'RM-GH200'").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsStockAboveLocationCapacity() {
    assertThatThrownBy(() -> jdbc.sql(
        "update stock set on_hand = 100000 where material_id = 'RM-GH200'").update())
        .hasMessageContaining("Kapazität");
  }

  @Test
  void databaseRejectsDuplicateReservationRequest() {
    String insert = """
        insert into reservation (id, request_id, order_id, material_id, location_id, qty, created_at_ms)
        values (:id, 'REQ-1', 'PO-1', 'RM-GH200', 'RAW-A', 1, 0)
        """;
    jdbc.sql(insert).param("id", "RES-1").update();
    assertThatThrownBy(() -> jdbc.sql(insert).param("id", "RES-2").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsSecondActiveTransportForTheSameAgv() {
    String insert = """
        insert into transport_order (id, request_id, kind, qty, from_node, to_node, priority, status, agv_id, created_at_ms)
        values (:id, :id, 'SUPPLY', 1, 'D_RAW', 'D_CNC01', 'NORMAL', :status, 'AGV-01', 0)
        """;
    jdbc.sql(insert).param("id", "TO-1").param("status", "IN_TRANSIT").update();
    jdbc.sql(insert).param("id", "TO-2").param("status", "DELIVERED").update(); // finished ones do not count
    assertThatThrownBy(() -> jdbc.sql(insert).param("id", "TO-3").param("status", "ASSIGNED").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}

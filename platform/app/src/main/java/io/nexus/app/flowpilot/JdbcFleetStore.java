package io.nexus.app.flowpilot;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.flowpilot.store.AgvRecord;
import io.nexus.flowpilot.store.FleetStore;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** FleetStore on PostgreSQL: AGV business state and edge blocks. */
@Component
class JdbcFleetStore implements FleetStore {
  private final JdbcClient jdbc;

  JdbcFleetStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AgvRecord> agvs() {
    return jdbc.sql("select id, name, status, node_id, battery, enabled, version from agv order by id collate \"C\"")
        .query((rs, i) -> new AgvRecord(rs.getString("id"), rs.getString("name"),
            AgvStatus.valueOf(rs.getString("status")), rs.getString("node_id"), rs.getDouble("battery"),
            rs.getBoolean("enabled"), rs.getInt("version")))
        .list();
  }

  @Override
  public AgvRecord save(AgvRecord a) {
    int rows = jdbc.sql("""
        update agv set status = :s, node_id = :n, battery = :b, enabled = :e, version = version + 1
         where id = :id and version = :v
        """).param("s", a.status().name()).param("n", a.nodeId()).param("b", a.battery()).param("e", a.enabled())
        .param("id", a.id()).param("v", a.version()).update();
    if (rows == 0) {
      boolean exists = jdbc.sql("select count(*) from agv where id = :id").param("id", a.id())
          .query(Integer.class).single() > 0;
      if (!exists) throw DomainException.notFound("AGV", a.id());
      throw DomainException.conflict("AGV " + a.id() + " wurde zwischenzeitlich geändert");
    }
    return new AgvRecord(a.id(), a.name(), a.status(), a.nodeId(), a.battery(), a.enabled(), a.version() + 1);
  }

  @Override
  public Set<String> blockedEdges() {
    return Collections.unmodifiableSet(new TreeSet<>(
        jdbc.sql("select id from graph_edge where blocked").query(String.class).list()));
  }

  @Override
  public void setEdgeBlocked(String edgeId, boolean blocked) {
    int rows = jdbc.sql("update graph_edge set blocked = :b where id = :id").param("b", blocked).param("id", edgeId)
        .update();
    if (rows == 0) throw DomainException.notFound("Kante", edgeId);
  }
}

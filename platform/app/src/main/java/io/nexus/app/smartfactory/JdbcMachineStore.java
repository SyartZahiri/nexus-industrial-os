package io.nexus.app.smartfactory;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.StateInterval;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** MachineStore on PostgreSQL: the machine table and machine_state_interval. */
@Component
class JdbcMachineStore implements MachineStore {
  private final JdbcClient jdbc;

  JdbcMachineStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<MachineRecord> machines() {
    return jdbc.sql("select id, status, last_product_id, version from machine order by id")
        .query((rs, i) -> new MachineRecord(rs.getString(1), MachineStatus.valueOf(rs.getString(2)), rs.getString(3),
            rs.getInt(4))).list();
  }

  @Override
  public MachineRecord save(MachineRecord m) {
    int rows = jdbc.sql("""
        update machine set status = :status, last_product_id = :product, version = version + 1
        where id = :id and version = :version
        """).param("id", m.id()).param("status", m.status().name()).param("product", m.lastProductId())
        .param("version", m.version()).update();
    if (rows == 0) throw DomainException.conflict("Maschine " + m.id() + " wurde zwischenzeitlich geändert");
    return new MachineRecord(m.id(), m.status(), m.lastProductId(), m.version() + 1);
  }

  @Override
  public void changeState(String machineId, MachineStatus status, long nowMs) {
    jdbc.sql("update machine_state_interval set to_ms = :now where machine_id = :m and to_ms is null")
        .param("now", nowMs).param("m", machineId).update();
    jdbc.sql("insert into machine_state_interval (machine_id, status, from_ms) values (:m, :s, :now)")
        .param("m", machineId).param("s", status.name()).param("now", nowMs).update();
  }

  @Override
  public List<StateInterval> intervals(String machineId, long fromMs, long toMs) {
    return jdbc.sql("""
        select status, greatest(from_ms, :from) as f, least(coalesce(to_ms, :to), :to) as t
        from machine_state_interval
        where machine_id = :m and from_ms < :to and (to_ms is null or to_ms > :from)
        order by from_ms, id
        """).param("m", machineId).param("from", fromMs).param("to", toMs)
        .query((rs, i) -> new StateInterval(machineId, MachineStatus.valueOf(rs.getString(1)), rs.getLong(2),
            rs.getLong(3))).list();
  }

  @Override
  public List<MaintenanceWindow> maintenanceWindows(String machineId) {
    return jdbc.sql("select start_ms, end_ms from maintenance_window where machine_id = :m order by start_ms, id")
        .param("m", machineId).query((rs, i) -> new MaintenanceWindow(machineId, rs.getLong(1), rs.getLong(2))).list();
  }
}

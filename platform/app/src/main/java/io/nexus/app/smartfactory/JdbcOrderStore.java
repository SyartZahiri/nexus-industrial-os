package io.nexus.app.smartfactory;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.ProcessType;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OperationMaterial;
import io.nexus.smartfactory.store.OrderStore;
import io.nexus.smartfactory.store.ProductionOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** OrderStore on PostgreSQL. Writes take part in the caller's transaction. */
@Component
class JdbcOrderStore implements OrderStore {
  private static final String ORDER = """
      select id, product_id, quantity, priority, status, due_at_ms, created_at_ms, completed_at_ms, good, scrapped, version
      from production_order""";
  private static final String OPERATION = """
      select id, order_id, seq, process, machine_id, status, planned_start_ms, planned_end_ms, started_at_ms,
             ended_at_ms, good, scrap
      from operation""";
  private static final String MATERIAL = """
      select id, order_id, step_seq, kind, material_id, quantity, transport_id, delivered from operation_material""";

  private final JdbcClient jdbc;

  JdbcOrderStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(ProductionOrder o, List<Operation> operations) {
    jdbc.sql("""
        insert into production_order (id, product_id, quantity, priority, status, due_at_ms, created_at_ms,
                                      completed_at_ms, good, scrapped, version)
        values (:id, :product, :qty, :prio, :status, :due, :created, :completed, :good, :scrapped, :version)
        """).param("id", o.id()).param("product", o.productId()).param("qty", o.quantity())
        .param("prio", o.priority().name()).param("status", o.status().name()).param("due", o.dueAtMs())
        .param("created", o.createdAtMs()).param("completed", o.completedAtMs()).param("good", o.good())
        .param("scrapped", o.scrapped()).param("version", o.version()).update();
    for (Operation op : operations) saveOperation(op);
  }

  @Override
  public ProductionOrder update(ProductionOrder o) {
    int rows = jdbc.sql("""
        update production_order set quantity = :qty, priority = :prio, status = :status, due_at_ms = :due,
               completed_at_ms = :completed, good = :good, scrapped = :scrapped, version = version + 1
        where id = :id and version = :version
        """).param("id", o.id()).param("qty", o.quantity()).param("prio", o.priority().name())
        .param("status", o.status().name()).param("due", o.dueAtMs()).param("completed", o.completedAtMs())
        .param("good", o.good()).param("scrapped", o.scrapped()).param("version", o.version()).update();
    if (rows == 0) {
      if (order(o.id()).isEmpty()) throw DomainException.notFound("Auftrag", o.id());
      throw DomainException.conflict("Auftrag " + o.id() + " wurde zwischenzeitlich geändert");
    }
    return order(o.id()).orElseThrow();
  }

  @Override
  public Optional<ProductionOrder> order(String id) {
    return jdbc.sql(ORDER + " where id = :id").param("id", id).query(JdbcOrderStore::order).optional();
  }

  @Override
  public List<ProductionOrder> orders() {
    return jdbc.sql(ORDER + " order by id").query(JdbcOrderStore::order).list();
  }

  @Override
  public List<ProductionOrder> open() {
    return jdbc.sql(ORDER + " where status not in ('COMPLETED', 'CANCELLED') order by id")
        .query(JdbcOrderStore::order).list();
  }

  @Override
  public List<Operation> operations(String orderId) {
    return jdbc.sql(OPERATION + " where order_id = :o order by seq").param("o", orderId)
        .query(JdbcOrderStore::operation).list();
  }

  @Override
  public void saveOperation(Operation op) {
    jdbc.sql("""
        insert into operation (id, order_id, seq, process, machine_id, status, planned_start_ms, planned_end_ms,
                               started_at_ms, ended_at_ms, good, scrap)
        values (:id, :order, :seq, :process, :machine, :status, :ps, :pe, :sa, :ea, :good, :scrap)
        on conflict (id) do update set machine_id = excluded.machine_id, status = excluded.status,
          planned_start_ms = excluded.planned_start_ms, planned_end_ms = excluded.planned_end_ms,
          started_at_ms = excluded.started_at_ms, ended_at_ms = excluded.ended_at_ms,
          good = excluded.good, scrap = excluded.scrap
        """).param("id", op.id()).param("order", op.orderId()).param("seq", op.seq())
        .param("process", op.process().name()).param("machine", op.machineId()).param("status", op.status().name())
        .param("ps", op.plannedStartMs()).param("pe", op.plannedEndMs()).param("sa", op.startedAtMs())
        .param("ea", op.endedAtMs()).param("good", op.good()).param("scrap", op.scrap()).update();
  }

  @Override
  public List<Operation> operationsOnMachine(String machineId) {
    return jdbc.sql(OPERATION + " where machine_id = :m and status not in ('DONE', 'CANCELLED') order by order_id, seq")
        .param("m", machineId).query(JdbcOrderStore::operation).list();
  }

  @Override
  public void saveMaterial(OperationMaterial m) {
    jdbc.sql("""
        insert into operation_material (id, order_id, step_seq, kind, material_id, quantity, transport_id, delivered)
        values (:id, :order, :seq, :kind, :material, :qty, :transport, :delivered)
        on conflict (id) do update set transport_id = excluded.transport_id, delivered = excluded.delivered,
          quantity = excluded.quantity
        """).param("id", m.id()).param("order", m.orderId()).param("seq", m.stepSeq()).param("kind", m.kind().name())
        .param("material", m.materialId()).param("qty", m.quantity()).param("transport", m.transportId())
        .param("delivered", m.delivered()).update();
  }

  @Override
  public List<OperationMaterial> materials(String orderId, int stepSeq) {
    return jdbc.sql(MATERIAL + " where order_id = :o and step_seq = :s order by id").param("o", orderId)
        .param("s", stepSeq).query(JdbcOrderStore::material).list();
  }

  @Override
  public Optional<OperationMaterial> materialByTransport(String transportId) {
    return jdbc.sql(MATERIAL + " where transport_id = :t").param("t", transportId)
        .query(JdbcOrderStore::material).optional();
  }

  private static ProductionOrder order(ResultSet rs, int row) throws SQLException {
    return new ProductionOrder(rs.getString("id"), rs.getString("product_id"), rs.getInt("quantity"),
        Priority.valueOf(rs.getString("priority")), OrderStatus.valueOf(rs.getString("status")),
        rs.getLong("due_at_ms"), rs.getLong("created_at_ms"), rs.getObject("completed_at_ms", Long.class),
        rs.getInt("good"), rs.getInt("scrapped"), rs.getInt("version"));
  }

  private static Operation operation(ResultSet rs, int row) throws SQLException {
    return new Operation(rs.getString("id"), rs.getString("order_id"), rs.getInt("seq"),
        ProcessType.valueOf(rs.getString("process")), rs.getString("machine_id"),
        Operation.Status.valueOf(rs.getString("status")), rs.getObject("planned_start_ms", Long.class),
        rs.getObject("planned_end_ms", Long.class), rs.getObject("started_at_ms", Long.class),
        rs.getObject("ended_at_ms", Long.class), rs.getInt("good"), rs.getInt("scrap"));
  }

  private static OperationMaterial material(ResultSet rs, int row) throws SQLException {
    return new OperationMaterial(rs.getString("id"), rs.getString("order_id"), rs.getInt("step_seq"),
        TransportKind.valueOf(rs.getString("kind")), rs.getString("material_id"), rs.getInt("quantity"),
        rs.getString("transport_id"), rs.getBoolean("delivered"));
  }
}

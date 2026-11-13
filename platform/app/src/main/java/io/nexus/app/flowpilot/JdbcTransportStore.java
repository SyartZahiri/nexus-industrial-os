package io.nexus.app.flowpilot;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.flowpilot.store.TransportStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** TransportStore on PostgreSQL with optimistic locking on version. */
@Component
class JdbcTransportStore implements TransportStore {
  private static final String SELECT = """
      select id, request_id, kind, order_id, material_id, qty, from_kind, from_id, from_node, to_kind, to_id, to_node,
             priority, status, agv_id, reservation_id, created_at_ms, version
      from transport_order
      """;

  private final JdbcClient jdbc;

  JdbcTransportStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(TransportOrder t) {
    try {
      jdbc.sql("""
          insert into transport_order (id, request_id, kind, order_id, material_id, qty, from_kind, from_id, from_node,
                                       to_kind, to_id, to_node, priority, status, agv_id, reservation_id,
                                       created_at_ms, version)
          values (:id, :req, :kind, :ord, :mat, :qty, :fk, :fi, :fn, :tk, :ti, :tn, :prio, :status, :agv, :res,
                  :created, :version)
          """).param("id", t.id()).param("req", t.requestId()).param("kind", t.kind().name())
          .param("ord", t.orderId()).param("mat", t.materialId()).param("qty", t.quantity())
          .param("fk", t.from().kind().name()).param("fi", t.from().id()).param("fn", t.fromNode())
          .param("tk", t.to().kind().name()).param("ti", t.to().id()).param("tn", t.toNode())
          .param("prio", t.priority().name()).param("status", t.status().name()).param("agv", t.agvId())
          .param("res", t.reservationId()).param("created", t.createdAtMs()).param("version", t.version())
          .update();
    } catch (DuplicateKeyException e) {
      throw DomainException.conflict("Transportanforderung " + t.requestId() + " existiert bereits");
    }
  }

  @Override
  public TransportOrder update(TransportOrder t) {
    int rows = jdbc.sql("""
        update transport_order
           set order_id = :ord, material_id = :mat, qty = :qty, from_kind = :fk, from_id = :fi, from_node = :fn,
               to_kind = :tk, to_id = :ti, to_node = :tn, priority = :prio, status = :status, agv_id = :agv,
               reservation_id = :res, version = version + 1
         where id = :id and version = :version
        """).param("id", t.id()).param("ord", t.orderId()).param("mat", t.materialId()).param("qty", t.quantity())
        .param("fk", t.from().kind().name()).param("fi", t.from().id()).param("fn", t.fromNode())
        .param("tk", t.to().kind().name()).param("ti", t.to().id()).param("tn", t.toNode())
        .param("prio", t.priority().name()).param("status", t.status().name()).param("agv", t.agvId())
        .param("res", t.reservationId()).param("version", t.version())
        .update();
    if (rows == 0) {
      if (byId(t.id()).isEmpty()) throw DomainException.notFound("Transportauftrag", t.id());
      throw DomainException.conflict("Transportauftrag " + t.id() + " wurde zwischenzeitlich geändert");
    }
    return new TransportOrder(t.id(), t.requestId(), t.kind(), t.orderId(), t.materialId(), t.quantity(), t.from(),
        t.fromNode(), t.to(), t.toNode(), t.priority(), t.status(), t.agvId(), t.reservationId(), t.createdAtMs(),
        t.version() + 1);
  }

  @Override
  public Optional<TransportOrder> byId(String id) {
    return jdbc.sql(SELECT + " where id = :id").param("id", id).query((rs, i) -> order(rs)).optional();
  }

  @Override
  public Optional<TransportOrder> byRequest(String requestId) {
    return jdbc.sql(SELECT + " where request_id = :r").param("r", requestId).query((rs, i) -> order(rs)).optional();
  }

  @Override
  public List<TransportOrder> open() {
    return jdbc.sql(SELECT + """
         where status not in ('DELIVERED', 'CANCELLED')
         order by case priority when 'EILIG' then 0 when 'HOCH' then 1 else 2 end, created_at_ms, id collate "C"
        """).query((rs, i) -> order(rs)).list();
  }

  @Override
  public List<TransportOrder> all() {
    return jdbc.sql(SELECT + " order by id collate \"C\"").query((rs, i) -> order(rs)).list();
  }

  private static TransportOrder order(ResultSet rs) throws SQLException {
    return new TransportOrder(rs.getString("id"), rs.getString("request_id"),
        TransportKind.valueOf(rs.getString("kind")), rs.getString("order_id"), rs.getString("material_id"),
        rs.getInt("qty"), new Place(Place.Kind.valueOf(rs.getString("from_kind")), rs.getString("from_id")),
        rs.getString("from_node"), new Place(Place.Kind.valueOf(rs.getString("to_kind")), rs.getString("to_id")),
        rs.getString("to_node"), Priority.valueOf(rs.getString("priority")),
        TransportStatus.valueOf(rs.getString("status")), rs.getString("agv_id"), rs.getString("reservation_id"),
        rs.getLong("created_at_ms"), rs.getInt("version"));
  }
}

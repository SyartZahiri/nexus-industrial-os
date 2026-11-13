package io.nexus.app.flowpilot;

import io.nexus.flowpilot.store.Reservation;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.StockStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** StockStore on PostgreSQL. Ids are ordered with collate "C", the order the core uses (String.compareTo). */
@Component
class JdbcStockStore implements StockStore {
  private static final String RESERVATION_COLUMNS =
      "select id, request_id, order_id, material_id, location_id, qty, status, created_at_ms from reservation ";

  private final JdbcClient jdbc;

  JdbcStockStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<StockLine> lockMaterial(String materialId) {
    return jdbc.sql("select material_id, location_id, on_hand, reserved from stock where material_id = :m "
            + "order by location_id collate \"C\" for update")
        .param("m", materialId).query((rs, i) -> line(rs)).list();
  }

  @Override
  public void saveLine(StockLine line) {
    jdbc.sql("""
        insert into stock (material_id, location_id, on_hand, reserved) values (:m, :l, :h, :r)
        on conflict (material_id, location_id) do update set on_hand = excluded.on_hand, reserved = excluded.reserved
        """).param("m", line.materialId()).param("l", line.locationId()).param("h", line.onHand())
        .param("r", line.reserved()).update();
  }

  @Override
  public List<StockLine> lines() {
    return jdbc.sql("select material_id, location_id, on_hand, reserved from stock "
            + "order by material_id collate \"C\", location_id collate \"C\"")
        .query((rs, i) -> line(rs)).list();
  }

  @Override
  public List<Reservation> reservationsOfRequest(String requestId) {
    return jdbc.sql(RESERVATION_COLUMNS + "where request_id = :r order by material_id collate \"C\"")
        .param("r", requestId).query((rs, i) -> reservation(rs)).list();
  }

  @Override
  public Optional<Reservation> reservation(String id) {
    return jdbc.sql(RESERVATION_COLUMNS + "where id = :id").param("id", id).query((rs, i) -> reservation(rs))
        .optional();
  }

  @Override
  public void saveReservation(Reservation r) {
    jdbc.sql("""
        insert into reservation (id, request_id, order_id, material_id, location_id, qty, status, created_at_ms)
        values (:id, :req, :ord, :m, :l, :q, :s, :t)
        on conflict (id) do update set status = excluded.status
        """).param("id", r.id()).param("req", r.requestId()).param("ord", r.orderId()).param("m", r.materialId())
        .param("l", r.locationId()).param("q", r.quantity()).param("s", r.status().name())
        .param("t", r.createdAtMs()).update();
  }

  @Override
  public List<Reservation> reservationsOfOrder(String orderId) {
    return jdbc.sql(RESERVATION_COLUMNS + "where order_id = :o order by id collate \"C\"")
        .param("o", orderId).query((rs, i) -> reservation(rs)).list();
  }

  private static StockLine line(ResultSet rs) throws SQLException {
    return new StockLine(rs.getString("material_id"), rs.getString("location_id"), rs.getInt("on_hand"),
        rs.getInt("reserved"));
  }

  private static Reservation reservation(ResultSet rs) throws SQLException {
    return new Reservation(rs.getString("id"), rs.getString("request_id"), rs.getString("order_id"),
        rs.getString("material_id"), rs.getString("location_id"), rs.getInt("qty"),
        Reservation.Status.valueOf(rs.getString("status")), rs.getLong("created_at_ms"));
  }
}

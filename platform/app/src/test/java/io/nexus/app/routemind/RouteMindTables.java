package io.nexus.app.routemind;

import org.springframework.jdbc.core.simple.JdbcClient;

/** RouteMind's tables back to master data: no deliveries, no tours, every vehicle AVAILABLE at the depot. */
public final class RouteMindTables {
  public static final String[] SQL_RESET = {
      "delete from tour_stop",
      "delete from tour",
      "delete from delivery_order",
      "update delivery_vehicle set status = 'AVAILABLE', tour_id = null, leg = 0, leg_start_ms = 0, leg_end_ms = 0, "
          + "breakdown_at_ms = null, ready_at_ms = 0, version = 0"};

  private RouteMindTables() {}

  public static void reset(JdbcClient jdbc) {
    for (String sql : SQL_RESET) jdbc.sql(sql).update();
  }
}

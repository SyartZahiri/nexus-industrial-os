package io.nexus.app.smartfactory;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties SmartFactory's movement tables and resets the machines, so a test starts like a fresh run. */
final class SmartFactoryTables {
  private SmartFactoryTables() {}

  static void clear(JdbcClient jdbc) {
    jdbc.sql("delete from operation_material").update();
    jdbc.sql("delete from operation").update();
    jdbc.sql("delete from production_order").update();
    jdbc.sql("delete from machine_state_interval").update();
    jdbc.sql("update machine set status = 'IDLE', last_product_id = null, version = 0").update();
  }
}

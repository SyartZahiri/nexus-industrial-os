package io.nexus.app.routemind;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.RouteStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class JdbcRouteStoreTest extends RouteStoreContract {
  @Autowired RouteStore store;
  @Autowired UnitOfWork work;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void startFromMasterData() {
    RouteMindTables.reset(jdbc);
  }

  @Override protected RouteStore store() { return store; }
  @Override protected UnitOfWork work() { return work; }

  @Test
  void vehiclesMatchMasterData() {
    var db = jdbc.sql("select id, name, max_kg, max_pallets from delivery_vehicle order by id collate \"C\"")
        .query((rs, i) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3) + "|" + rs.getInt(4)).list();
    var core = RouteMindMasterData.vehicles().stream()
        .map(v -> v.id() + "|" + v.name() + "|" + v.maxKg() + "|" + v.maxPallets()).toList();
    assertThat(core).isEqualTo(db);
  }
}

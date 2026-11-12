package io.nexus.app.flowpilot;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.flowpilot.store.FleetStore;
import io.nexus.flowpilot.store.StockStore;
import io.nexus.flowpilot.store.StoreContract;
import io.nexus.flowpilot.store.TransportStore;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class JdbcStoreTest extends StoreContract {
  @Autowired StockStore stock;
  @Autowired TransportStore transports;
  @Autowired FleetStore fleet;
  @Autowired UnitOfWork work;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void startFromMasterData() {
    FlowPilotTestData.reset(jdbc);
  }

  @Override protected StockStore stock() { return stock; }
  @Override protected TransportStore transports() { return transports; }
  @Override protected FleetStore fleet() { return fleet; }
  @Override protected UnitOfWork work() { return work; }
}

package io.nexus.app.smartfactory;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.OrderStore;
import io.nexus.smartfactory.store.StoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** The same contract the in-memory stores pass, against PostgreSQL. */
@SpringBootTest
@ActiveProfiles("embedded")
class JdbcStoreTest extends StoreContract {
  @Autowired JdbcClient jdbc;
  @Autowired UnitOfWork work;
  @Autowired JdbcOrderStore orders;
  @Autowired JdbcMachineStore machines;

  @BeforeEach
  void clean() {
    SmartFactoryTables.clear(jdbc);
  }

  @Override protected OrderStore orders() { return orders; }
  @Override protected MachineStore machines() { return machines; }
  @Override protected UnitOfWork work() { return work; }
}

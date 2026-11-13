package io.nexus.app.smartfactory;

import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MachineControl;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.smartfactory.SmartFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SmartFactory on PostgreSQL. The bean is also the MachineFeedback the simulation calls.
 * FlowPilot's ports and MachineControl are looked up on first use, so the context starts on any branch.
 */
@Configuration
class SmartFactoryConfig {
  @Bean
  SmartFactory smartFactory(JdbcOrderStore orders, JdbcMachineStore machines, EventBus bus, UnitOfWork work,
                            SimTime time, IdSource ids, ObjectProvider<MaterialService> materials,
                            ObjectProvider<TransportService> transports, ObjectProvider<MachineControl> control) {
    return new SmartFactory(orders, machines, bus, work, time, ids, materials::getObject, transports::getObject,
        control::getObject);
  }

  @Bean
  ProductCatalog productCatalog(SmartFactory factory) {
    return factory.catalog();
  }
}

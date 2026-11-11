package io.nexus.app.event;

import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventLog;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.sim.SimTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the EventBus of the core to PostgreSQL and Spring transactions. */
@Configuration
class EventConfig {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EventConfig.class);
  @Bean
  SimTime simTime() {
    return new SimTime();
  }

  /** Also serves as the EventPublisher bean. Every EventConsumer bean is registered; duplicate names fail startup. */
  @Bean
  EventBus eventBus(EventLog log, UnitOfWork work, SimTime time, SimRun run, ObjectProvider<EventConsumer> consumers) {
    var bus = new EventBus(log, work, time, run::ids, e -> LOG.error("Ereignisverteilung: interner Fehler", e));
    consumers.orderedStream().forEach(bus::register);
    return bus;
  }
}

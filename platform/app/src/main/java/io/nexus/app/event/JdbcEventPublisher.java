package io.nexus.app.event;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.sim.SimTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcEventPublisher implements EventPublisher {
  private static final Logger log = LoggerFactory.getLogger(JdbcEventPublisher.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final SimRun run;
  private final SimTime time;
  private final ObjectMapper json;
  private final ObjectProvider<EventConsumer> consumers;
  private final TransactionTemplate newTx;

  JdbcEventPublisher(JdbcClient jdbc, SimRun run, SimTime time, ObjectMapper json,
                     ObjectProvider<EventConsumer> consumers, PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.run = run;
    this.time = time;
    this.json = json;
    this.consumers = consumers;
    this.newTx = new TransactionTemplate(txManager);
    this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public NexusEvent publish(String type, String source, Severity severity, String correlationId,
                            Map<String, Object> payload) {
    var event = new NexusEvent(run.ids().next(), time.nowMs(), type, source, severity, correlationId, payload);
    jdbc.sql("""
        insert into event (event_id, run_id, sim_time_ms, type, source, severity, correlation_id, payload)
        values (:id, :run, :time, :type, :source, :severity, :corr, cast(:payload as jsonb))
        """)
        .param("id", event.eventId()).param("run", run.id()).param("time", event.simTimeMs())
        .param("type", type).param("source", source).param("severity", severity.name())
        .param("corr", correlationId).param("payload", json.writeValueAsString(payload))
        .update();

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          deliver(event);
        }
      });
    } else {
      deliver(event);
    }
    return event;
  }

  /** Delivers a stored event again. Consumers that already processed it are skipped. */
  public void redeliver(UUID eventId) {
    NexusEvent event = jdbc.sql("""
        select event_id, sim_time_ms, type, source, severity, correlation_id, payload::text as payload
        from event where event_id = :id
        """).param("id", eventId)
        .query((rs, i) -> new NexusEvent(rs.getObject("event_id", UUID.class), rs.getLong("sim_time_ms"),
            rs.getString("type"), rs.getString("source"), Severity.valueOf(rs.getString("severity")),
            rs.getString("correlation_id"), json.readValue(rs.getString("payload"), MAP)))
        .single();
    deliver(event);
  }

  private void deliver(NexusEvent event) {
    // fixed order keeps the simulation deterministic
    List<EventConsumer> ordered = consumers.stream().sorted(Comparator.comparing(EventConsumer::name)).toList();
    for (EventConsumer consumer : ordered) {
      try {
        // marker and the consumer's own writes commit together or not at all
        newTx.executeWithoutResult(status -> {
          int first = jdbc.sql("""
              insert into processed_event (consumer, event_id) values (:c, :e) on conflict do nothing
              """).param("c", consumer.name()).param("e", event.eventId()).update();
          if (first == 1) consumer.on(event);
        });
      } catch (RuntimeException ex) {
        log.error("Verbraucher {} konnte Ereignis {} ({}) nicht verarbeiten", consumer.name(), event.eventId(),
            event.type(), ex);
      }
    }
  }
}

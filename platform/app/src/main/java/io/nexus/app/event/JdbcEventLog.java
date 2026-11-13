package io.nexus.app.event;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventLog;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** EventLog on PostgreSQL. Payload records are stored as JSON and read back by the type registered for the event. */
@Component
public class JdbcEventLog implements EventLog {
  private static final String EVENT_COLUMNS =
      "e.event_id, e.sim_time_ms, e.type, e.source, e.severity, e.correlation_id, e.payload::text as payload";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcEventLog(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void append(long runId, NexusEvent event) {
    jdbc.sql("""
        insert into event (event_id, run_id, sim_time_ms, type, source, severity, correlation_id, payload)
        values (:id, :run, :time, :type, :source, :severity, :corr, cast(:payload as jsonb))
        """)
        .param("id", event.eventId()).param("run", runId).param("time", event.simTimeMs())
        .param("type", event.type()).param("source", event.source()).param("severity", event.severity().name())
        .param("corr", event.correlationId()).param("payload", json.writeValueAsString(event.payload()))
        .update();
  }

  @Override
  public boolean markProcessed(String consumer, String eventId) {
    return jdbc.sql("insert into processed_event (consumer, event_id) values (:c, :e) on conflict do nothing")
        .param("c", consumer).param("e", eventId).update() == 1;
  }

  @Override
  public int recordFailure(String consumer, String eventId, String error) {
    return jdbc.sql("""
        insert into event_delivery_failure (consumer, event_id, attempts, last_error) values (:c, :e, 1, :err)
        on conflict (consumer, event_id)
          do update set attempts = event_delivery_failure.attempts + 1, last_error = excluded.last_error
        returning attempts
        """).param("c", consumer).param("e", eventId).param("err", error).query(Integer.class).single();
  }

  @Override
  public void clearFailure(String consumer, String eventId) {
    jdbc.sql("delete from event_delivery_failure where consumer = :c and event_id = :e")
        .param("c", consumer).param("e", eventId).update();
  }

  @Override
  public void park(String consumer, String eventId) {
    jdbc.sql("insert into event_delivery_failure (consumer, event_id, attempts) values (:c, :e, 0) on conflict do nothing")
        .param("c", consumer).param("e", eventId).update();
  }

  @Override
  public boolean hasOpenFailure(String consumer, long runId, int maxAttempts) {
    return jdbc.sql("""
        select exists (select 1 from event_delivery_failure f join event e on e.event_id = f.event_id
                       where f.consumer = :c and e.run_id = :run and f.attempts < :max)
        """).param("c", consumer).param("run", runId).param("max", maxAttempts).query(Boolean.class).single();
  }

  @Override
  public List<Failure> openFailures(long runId, int maxAttempts) {
    return jdbc.sql("select f.consumer, f.attempts, " + EVENT_COLUMNS + """
         from event_delivery_failure f join event e on e.event_id = f.event_id
        where e.run_id = :run and f.attempts < :max
        order by e.seq, f.consumer
        """).param("run", runId).param("max", maxAttempts)
        .query((rs, i) -> new Failure(rs.getString("consumer"), event(rs), rs.getInt("attempts")))
        .list();
  }

  @Override
  public Optional<NexusEvent> find(String eventId) {
    return jdbc.sql("select " + EVENT_COLUMNS + " from event e where e.event_id = :id").param("id", eventId)
        .query((rs, i) -> event(rs)).optional();
  }

  /** Every event of a run in publish order: the server's counterpart of the in-memory plant's events(). */
  public List<NexusEvent> eventsOfRun(long runId) {
    return jdbc.sql("select " + EVENT_COLUMNS + " from event e where e.run_id = :run order by e.seq")
        .param("run", runId).query((rs, i) -> event(rs)).list();
  }

  private NexusEvent event(ResultSet rs) throws SQLException {
    String type = rs.getString("type");
    return new NexusEvent(rs.getString("event_id"), rs.getLong("sim_time_ms"), type, rs.getString("source"),
        Severity.valueOf(rs.getString("severity")), rs.getString("correlation_id"),
        json.readValue(rs.getString("payload"), EventTypes.payloadType(type)));
  }
}

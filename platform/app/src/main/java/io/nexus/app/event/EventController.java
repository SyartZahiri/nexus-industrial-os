package io.nexus.app.event;

import io.nexus.contracts.event.EventBus;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/events")
class EventController {
  record EventView(long seq, String eventId, long simTimeMs, String type, String source, String severity,
                   String correlationId, JsonNode payload) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final SimRun run;
  private final EventBus bus;

  EventController(JdbcClient jdbc, ObjectMapper json, SimRun run, EventBus bus) {
    this.jdbc = jdbc;
    this.json = json;
    this.run = run;
    this.bus = bus;
  }

  /**
   * Events of the current simulation run in ascending order. Pass the last seen seq as {@code after} to page forward.
   * Read under the platform lock: no publishing transaction is open, so no lower seq can appear after this page.
   */
  @GetMapping
  List<EventView> events(@RequestParam(required = false) String type,
                         @RequestParam(required = false) String source,
                         @RequestParam(required = false) String correlationId,
                         @RequestParam(defaultValue = "0") long after,
                         @RequestParam(defaultValue = "200") int limit) {
    synchronized (bus) {
      return jdbc.sql("""
          select seq, event_id, sim_time_ms, type, source, severity, correlation_id, payload::text as payload
          from event
          where run_id = :run
            and seq > :after
            and (cast(:type as text) is null or type = :type)
            and (cast(:source as text) is null or source = :source)
            and (cast(:corr as text) is null or correlation_id = :corr)
          order by seq
          limit :limit
          """)
          .param("run", run.id()).param("after", after).param("type", type).param("source", source).param("corr", correlationId)
          .param("limit", Math.clamp(limit, 1, 1000))
          .query((rs, i) -> new EventView(rs.getLong("seq"), rs.getString("event_id"),
              rs.getLong("sim_time_ms"), rs.getString("type"), rs.getString("source"), rs.getString("severity"),
              rs.getString("correlation_id"), json.readTree(rs.getString("payload"))))
          .list();
    }
  }
}

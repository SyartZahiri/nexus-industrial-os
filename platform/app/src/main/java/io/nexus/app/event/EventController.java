package io.nexus.app.event;

import java.util.List;
import java.util.UUID;
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
  record EventView(long seq, UUID eventId, long simTimeMs, String type, String source, String severity,
                   String correlationId, JsonNode payload) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  EventController(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Events in ascending order. Pass the last seen seq as {@code after} to page forward. */
  @GetMapping
  List<EventView> events(@RequestParam(required = false) String type,
                         @RequestParam(required = false) String source,
                         @RequestParam(required = false) String correlationId,
                         @RequestParam(defaultValue = "0") long after,
                         @RequestParam(defaultValue = "200") int limit) {
    return jdbc.sql("""
        select seq, event_id, sim_time_ms, type, source, severity, correlation_id, payload::text as payload
        from event
        where seq > :after
          and (cast(:type as text) is null or type = :type)
          and (cast(:source as text) is null or source = :source)
          and (cast(:corr as text) is null or correlation_id = :corr)
        order by seq
        limit :limit
        """)
        .param("after", after).param("type", type).param("source", source).param("corr", correlationId)
        .param("limit", Math.clamp(limit, 1, 1000))
        .query((rs, i) -> new EventView(rs.getLong("seq"), rs.getObject("event_id", UUID.class),
            rs.getLong("sim_time_ms"), rs.getString("type"), rs.getString("source"), rs.getString("severity"),
            rs.getString("correlation_id"), json.readTree(rs.getString("payload"))))
        .list();
  }
}

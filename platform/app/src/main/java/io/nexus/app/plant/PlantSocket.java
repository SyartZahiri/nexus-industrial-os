package io.nexus.app.plant;

import io.nexus.app.event.JdbcEventLog;
import io.nexus.app.event.SimRun;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.plant.Plant;
import io.nexus.plant.PlantJson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The live stream of the plant under /ws. One sender thread, every 100 ms:
 * <ul>
 *   <li>{"type":"reset","seed":…} when a new run started,</li>
 *   <li>{"type":"frame","frame":{…}} when the frame changed (the same JSON the browser runtime produces),</li>
 *   <li>{"type":"events","items":[…]} with every event of the current run since the last message.</li>
 * </ul>
 * Each client is wrapped so sending never blocks the others: a client that cannot keep up (send time over 2 s or
 * more than 1 MB queued) is disconnected. New clients get the current frame first and events from then on; the
 * history is in GET /api/v1/events.
 */
public class PlantSocket extends TextWebSocketHandler {
  private static final Logger log = LoggerFactory.getLogger(PlantSocket.class);
  static final long INTERVAL_MS = 100;

  private final Plant plant;
  private final JdbcEventLog events;
  private final SimRun run;
  private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();
  private ScheduledExecutorService sender;

  private long sentRun = -1;
  private long sentSeq;
  private String sentFrame;

  public PlantSocket(Plant plant, JdbcEventLog events, SimRun run) {
    this.plant = plant;
    this.events = events;
    this.run = run;
  }

  public PlantSocket start() {
    sender = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "nexus-ws");
      t.setDaemon(true);
      return t;
    });
    sender.scheduleWithFixedDelay(this::broadcastSafely, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    return this;
  }

  public void stop() {
    if (sender != null) sender.shutdownNow();
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    clients.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 2_000, 1_048_576));
    synchronized (this) {
      sentFrame = null;          // the newcomer needs a frame now, even if nothing changed
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    clients.remove(session.getId());
  }

  private void broadcastSafely() {
    try {
      broadcast();
    } catch (RuntimeException e) {
      log.warn("WebSocket-Versand fehlgeschlagen", e);  // the next tick tries again
    }
  }

  private synchronized void broadcast() {
    long currentRun = run.id();
    if (clients.isEmpty()) {
      // nobody listens: only keep the position, so a newcomer does not get the whole history
      sentRun = currentRun;
      sentSeq = events.lastSeq(currentRun);
      return;
    }
    if (currentRun != sentRun) {
      if (sentRun != -1) send(new JsonWriter().beginObject().name("type").value("reset").name("seed").value(run.seed())
          .endObject().toString());
      sentRun = currentRun;
      sentSeq = 0;
      sentFrame = null;
    }
    String frame = PlantJson.frame(plant.frame());
    if (!frame.equals(sentFrame)) {
      send(new JsonWriter().beginObject().name("type").value("frame").name("frame").raw(frame).endObject().toString());
      sentFrame = frame;
    }
    List<JdbcEventLog.Sequenced> fresh = events.eventsAfter(currentRun, sentSeq, 1_000);
    if (!fresh.isEmpty()) {
      var w = new JsonWriter().beginObject().name("type").value("events").name("items").beginArray();
      for (JdbcEventLog.Sequenced e : fresh) w.raw(PlantJson.event(e.event(), true));
      send(w.endArray().endObject().toString());
      sentSeq = fresh.get(fresh.size() - 1).seq();
    }
  }

  private void send(String json) {
    var message = new TextMessage(json);
    for (WebSocketSession client : clients.values()) {
      try {
        if (client.isOpen()) client.sendMessage(message);
      } catch (IOException | IllegalStateException e) {
        clients.remove(client.getId());                 // closed or too slow; the decorator already closed it
      }
    }
  }
}

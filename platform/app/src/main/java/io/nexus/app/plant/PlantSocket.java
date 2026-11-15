package io.nexus.app.plant;

import io.nexus.app.event.JdbcEventLog;
import io.nexus.app.event.SimRun;
import io.nexus.contracts.event.EventBus;
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
 *   <li>{"type":"frame","frame":{…}} when the frame changed, at least once a second (the same JSON the browser
 *       runtime produces),</li>
 *   <li>{"type":"events","items":[{"seq":…,"eventId":…,…}]} with every event of the current run since the last
 *       message; a client that reconnects asks GET /api/v1/events?after=&lt;last seq&gt; for the gap.</li>
 * </ul>
 * Frame and events are read under the platform lock: while it is free, no publishing transaction is open, so no
 * event with a lower seq can still become visible later and be skipped.
 *
 * <p>Sending never blocks for long: each send is limited to 2 s, a client that cannot keep up is closed. At most
 * {@link #MAX_CLIENTS} clients are served; the API has no login (Spec 16), the limit keeps a careless or hostile
 * page from exhausting the server.
 */
public class PlantSocket extends TextWebSocketHandler {
  private static final Logger log = LoggerFactory.getLogger(PlantSocket.class);
  static final long INTERVAL_MS = 100;
  public static final int MAX_CLIENTS = 50;
  private static final long FRAME_REFRESH_MS = 1_000;
  private static final long SEND_TIMEOUT_MS = 2_000;
  /**
   * Distinguishes this server process: the embedded database starts its run numbers at 1 again after a restart, so a
   * run id alone would let a reconnecting client take a new run for the one it knew.
   */
  private static final String BOOT = Long.toHexString(new java.security.SecureRandom().nextLong());

  private final Plant plant;
  private final JdbcEventLog events;
  private final SimRun run;
  private final EventBus bus;
  private final SimulationLoop loop;
  private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();
  private ScheduledExecutorService sender;

  private long sentRun = -1;
  private long sentSeq;
  private String sentFrame;
  private String sentSim;
  private long sentFrameSimTime = -1;
  private long sentFrameAt;
  private boolean failing;

  public PlantSocket(Plant plant, JdbcEventLog events, SimRun run, EventBus bus, SimulationLoop loop) {
    this.plant = plant;
    this.events = events;
    this.run = run;
    this.bus = bus;
    this.loop = loop;
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
    if (sender == null) return;
    sender.shutdownNow();
    try {
      sender.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    // Tomcat blocks a send for up to 20 s by default; one stalled tab must not hold up everyone else that long
    if (session instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession standard
        && standard.getNativeSession() != null) {
      standard.getNativeSession().getUserProperties()
          .put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", SEND_TIMEOUT_MS);
    }
    synchronized (this) {
      if (clients.size() >= MAX_CLIENTS) {
        session.close(CloseStatus.SERVICE_OVERLOAD);
        return;
      }
      if (clients.isEmpty()) {
        // the first listener starts at the newest event; older ones are history (GET /api/v1/events)
        synchronized (bus) {
          sentRun = run.id();
          sentSeq = events.lastSeq(sentRun);
        }
      }
      // the newcomer first learns which run it is in; after a reconnect it compares with the run it knew
      String hello;
      synchronized (bus) {
        hello = new JsonWriter().beginObject().name("type").value("hello").name("run").value(runKey(run.id()))
            .name("seed").value(run.seed()).endObject().toString();
      }
      // through the decorator, so the greeting has the same 2 s send limit as every other message; still under this
      // monitor (never under the platform lock), so it reaches the client before the sender thread's first frame
      var client = new ConcurrentWebSocketSessionDecorator(session, (int) SEND_TIMEOUT_MS, 1_048_576);
      client.sendMessage(new TextMessage(hello));
      clients.put(session.getId(), client);
      sentFrame = null;          // the newcomer needs a frame and the simulation state now
      sentSim = null;
    }
  }

  private static String runKey(long runId) {
    return BOOT + ":" + runId;
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    clients.remove(session.getId());
  }

  private void broadcastSafely() {
    try {
      broadcast();
      if (failing) log.info("WebSocket-Versand läuft wieder");
      failing = false;
    } catch (Throwable e) {                             // the sender thread must survive anything
      if (!failing) log.warn("WebSocket-Versand fehlgeschlagen; weitere Fehler bis zur Erholung ohne Log", e);
      failing = true;
    }
  }

  private synchronized void broadcast() {
    if (clients.isEmpty()) return;
    String reset = null;
    String frame = null;
    String sim = null;
    List<JdbcEventLog.Sequenced> fresh;
    synchronized (bus) {
      long currentRun = run.id();
      if (currentRun != sentRun) {
        reset = new JsonWriter().beginObject().name("type").value("reset").name("run").value(runKey(currentRun))
            .name("seed").value(run.seed()).endObject().toString();
        sentRun = currentRun;
        sentSeq = 0;
        sentFrame = null;
        sentSim = null;
      }
      String state = new JsonWriter().beginObject().name("type").value("sim").name("running").value(loop.running())
          .name("speed").value(loop.speed()).name("seed").value(run.seed()).endObject().toString();
      if (!state.equals(sentSim)) sim = state;
      fresh = events.eventsAfter(currentRun, sentSeq, 1_000);
      long now = System.currentTimeMillis();
      long simTime = plant.nowMs();
      if (sentFrame == null || simTime != sentFrameSimTime || !fresh.isEmpty() || now - sentFrameAt >= FRAME_REFRESH_MS) {
        frame = PlantJson.frame(plant.frame());
        sentFrameSimTime = simTime;
        sentFrameAt = now;
      }
    }
    if (reset != null) send(reset);
    if (sim != null) {
      send(sim);
      sentSim = sim;
    }
    if (frame != null && !frame.equals(sentFrame)) {
      send(new JsonWriter().beginObject().name("type").value("frame").name("frame").raw(frame).endObject().toString());
      sentFrame = frame;
    }
    if (!fresh.isEmpty()) {
      var w = new JsonWriter().beginObject().name("type").value("events").name("items").beginArray();
      for (JdbcEventLog.Sequenced e : fresh) {
        try {
          String json = PlantJson.event(e.event(), true);
          w.raw("{\"seq\":" + e.seq() + "," + json.substring(1));
        } catch (RuntimeException unwritable) {
          log.warn("Ereignis {} nicht als JSON darstellbar, übersprungen", e.seq(), unwritable);
        }
        sentSeq = e.seq();                              // a broken event never stalls the stream
      }
      send(w.endArray().endObject().toString());
    }
  }

  private void send(String json) {
    var message = new TextMessage(json);
    for (WebSocketSession client : clients.values()) {
      try {
        if (client.isOpen()) client.sendMessage(message);
        else clients.remove(client.getId());
      } catch (Exception tooSlowOrGone) {                 // includes the decorator's SessionLimitExceededException
        clients.remove(client.getId());
        try {
          client.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException | RuntimeException alreadyClosed) {
          // nothing left to close
        }
      }
    }
  }
}

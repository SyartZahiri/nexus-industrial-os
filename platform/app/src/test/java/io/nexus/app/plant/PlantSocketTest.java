package io.nexus.app.plant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.json.JsonReader;
import io.nexus.plant.Plant;
import io.nexus.smartfactory.orders.NewOrder;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
class PlantSocketTest {
  @Value("${local.server.port}") int port;
  @Autowired Plant plant;
  @Autowired PlantReset reset;

  @BeforeEach
  void freshPlant() {
    reset.reset(5);
  }

  /** A connected test client collecting every message as parsed JSON. */
  final class Client extends TextWebSocketHandler {
    final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
    WebSocketSession session;

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
      messages.add((Map<String, Object>) JsonReader.parse(message.getPayload()));
    }

    Client connect(String origin) throws Exception {
      var headers = new WebSocketHttpHeaders();
      if (origin != null) headers.setOrigin(origin);
      session = new StandardWebSocketClient().execute(this, headers, URI.create("ws://localhost:" + port + "/ws"))
          .get(10, TimeUnit.SECONDS);
      return this;
    }

    Map<String, Object> next(String type) throws InterruptedException {
      long deadline = System.currentTimeMillis() + 10_000;
      while (System.currentTimeMillis() < deadline) {
        var m = messages.poll(200, TimeUnit.MILLISECONDS);
        if (m != null && type.equals(m.get("type"))) return m;
      }
      throw new AssertionError("no " + type + " message");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> eventsFor(int count) throws InterruptedException {
      List<Map<String, Object>> all = new ArrayList<>();
      long deadline = System.currentTimeMillis() + 10_000;
      while (all.size() < count && System.currentTimeMillis() < deadline) {
        var m = messages.poll(200, TimeUnit.MILLISECONDS);
        if (m != null && "events".equals(m.get("type"))) all.addAll((List<Map<String, Object>>) m.get("items"));
      }
      return all;
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void clientReceivesAFrameAndNewEvents() throws Exception {
    var client = new Client().connect(null);
    var frame = (Map<String, Object>) client.next("frame").get("frame");
    assertThat((List<Object>) frame.get("machines")).hasSize(7);

    plant.smartFactory().createOrder(new NewOrder("FP12", 2, Priority.NORMAL, 3_600_000));
    var events = client.eventsFor(1);
    assertThat(events).extracting(e -> e.get("type")).contains("order.created");
    client.session.close();
  }

  @Test
  void eventsArriveOnceAndInOrder() throws Exception {
    var client = new Client().connect(null);
    client.next("frame");
    for (int i = 0; i < 3; i++) plant.smartFactory().createOrder(new NewOrder("AW50", 1, Priority.NORMAL, 3_600_000));
    List<Map<String, Object>> events = client.eventsFor(12);
    List<Long> counters = events.stream().map(e -> Long.parseLong(((String) e.get("eventId")).split("-")[1])).toList();
    assertThat(counters).doesNotHaveDuplicates().isSorted();
    assertThat(events.stream().filter(e -> "order.created".equals(e.get("type")))).hasSize(3);
    client.session.close();
  }

  /** A client that goes away does not stop the others. */
  @Test
  void aVanishedClientDoesNotStopTheOthers() throws Exception {
    var gone = new Client().connect(null);
    var stays = new Client().connect(null);
    stays.next("frame");
    gone.session.close();
    plant.smartFactory().createOrder(new NewOrder("FP12", 1, Priority.NORMAL, 3_600_000));
    assertThat(stays.eventsFor(1)).extracting(e -> e.get("type")).contains("order.created");
    stays.session.close();
  }

  @Test
  void resetIsAnnounced() throws Exception {
    var client = new Client().connect(null);
    client.next("frame");
    reset.reset(99);
    assertThat(client.next("reset")).containsEntry("seed", 99L);
    client.next("frame");
    client.session.close();
  }

  @Test
  void devServerOriginIsAllowedAndAForeignOneRejected() throws Exception {
    new Client().connect("http://localhost:5173").session.close();
    assertThatThrownBy(() -> new Client().connect("http://evil.example")).isInstanceOf(Exception.class);
  }
}

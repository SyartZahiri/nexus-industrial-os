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
  @Autowired io.nexus.contracts.event.EventBus bus;
  @Autowired io.nexus.contracts.event.UnitOfWork work;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @Autowired SimulationLoop loop;

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
  void theSimulationStateIsSentOnConnectAndOnChange() throws Exception {
    var client = new Client().connect(null);
    assertThat(client.next("sim")).containsEntry("running", false).containsEntry("seed", 5L);
    loop.setSpeed(5);
    assertThat(((Number) client.next("sim").get("speed")).doubleValue()).isEqualTo(5.0);   // JsonWriter writes 5.0 as 5
    loop.setSpeed(1);
    client.session.close();
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

  /**
   * Review C1: an outer unit inserts event A (seq n, not yet visible), an inner unit commits B (seq n+1) first.
   * A reader without the lock would move past n and never send A.
   */
  @Test
  void eventsCommittedOutOfInsertOrderAreNotLost() throws Exception {
    var client = new Client().connect(null);
    client.next("frame");
    Thread publisher = new Thread(() -> {
      synchronized (bus) {
        tx.executeWithoutResult(s -> {
          publish("PO-OUTER");
          work.inNewTransaction(() -> publish("PO-INNER"));
          try {
            Thread.sleep(600);                               // several sender ticks see only the inner event
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
    });
    publisher.start();
    publisher.join();
    var types = client.eventsFor(2).stream().map(e -> e.get("correlationId")).toList();
    assertThat(types).contains("PO-OUTER", "PO-INNER");
    client.session.close();
  }

  private void publish(String orderId) {
    bus.publish(io.nexus.contracts.event.EventTypes.ORDER_CREATED, "test", io.nexus.contracts.Domain.Severity.INFO,
        orderId, new io.nexus.contracts.event.Payloads.OrderCreated(orderId, "FP12", 1, Priority.NORMAL, 0));
  }

  @Test
  void everyEventItemCarriesItsSeqForGapFilling() throws Exception {
    var client = new Client().connect(null);
    client.next("frame");
    plant.smartFactory().createOrder(new NewOrder("FP12", 1, Priority.NORMAL, 3_600_000));
    assertThat(client.eventsFor(1)).allSatisfy(e -> assertThat(e.get("seq")).isInstanceOf(Long.class));
    client.session.close();
  }

  @Test
  void clientsBeyondTheLimitAreTurnedAway() throws Exception {
    List<Client> admitted = new ArrayList<>();
    try {
      for (int i = 0; i < PlantSocket.MAX_CLIENTS; i++) admitted.add(new Client().connect(null));
      var extra = new Client().connect(null);
      long deadline = System.currentTimeMillis() + 5_000;
      while (extra.session.isOpen() && System.currentTimeMillis() < deadline) Thread.sleep(50);
      assertThat(extra.session.isOpen()).as("the client over the limit is closed").isFalse();
    } finally {
      for (Client c : admitted) c.session.close();      // never leave 50 clients behind for the next test
    }
  }

  @Test
  void devServerOriginIsAllowedAndAForeignOneRejected() throws Exception {
    new Client().connect("http://localhost:5173").session.close();
    assertThatThrownBy(() -> new Client().connect("http://evil.example")).isInstanceOf(Exception.class);
  }
}

package io.nexus.smartfactory.testkit;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.TransportStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TransportService stand-in: records requests and delivers on demand by publishing transport.delivered,
 * like FlowPilot does when an AGV unloads. Idempotent on request ids; ids TO-0001, TO-0002, ...
 */
public final class FakeTransportService implements TransportService {
  /** @param status OPEN, PICKED_UP, DELIVERED or CANCELLED */
  public record FakeTransport(String id, TransportRequest request, Place to, String status) {}

  private final EventPublisher events;
  private final Map<String, FakeTransport> byId = new LinkedHashMap<>();
  private final Map<String, String> idByRequest = new LinkedHashMap<>();

  public FakeTransportService(EventPublisher events) {
    this.events = events;
  }

  @Override
  public String requestTransport(TransportRequest request) {
    String existing = idByRequest.get(request.requestId());
    if (existing != null) return existing;
    if (request.quantity() <= 0) throw DomainException.invalid("Menge muss positiv sein");
    String id = IdSource.format("TO", byId.size() + 1);
    byId.put(id, new FakeTransport(id, request, request.to(), "OPEN"));
    idByRequest.put(request.requestId(), id);
    return id;
  }

  @Override
  public void cancel(String transportId) {
    FakeTransport t = transport(transportId);
    if (t.status().equals("CANCELLED")) return;
    if (!t.status().equals("OPEN")) {
      throw new IllegalTransitionException(transportId, TransportStatus.IN_TRANSIT, TransportStatus.CANCELLED);
    }
    byId.put(transportId, new FakeTransport(t.id(), t.request(), t.to(), "CANCELLED"));
  }

  @Override
  public void redirect(String transportId, Place newTo) {
    FakeTransport t = transport(transportId);
    if (t.status().equals("DELIVERED") || t.status().equals("CANCELLED")) {
      throw new IllegalTransitionException(transportId, TransportStatus.DELIVERED, TransportStatus.IN_TRANSIT);
    }
    byId.put(transportId, new FakeTransport(t.id(), t.request(), newTo, t.status()));
  }

  /** The AGV has loaded: the transport can no longer be cancelled. */
  public void pickUp(String transportId) {
    FakeTransport t = transport(transportId);
    byId.put(transportId, new FakeTransport(t.id(), t.request(), t.to(), "PICKED_UP"));
  }

  /** Unloads at the current destination and publishes transport.delivered. */
  public void deliver(String transportId) {
    FakeTransport t = transport(transportId);
    byId.put(transportId, new FakeTransport(t.id(), t.request(), t.to(), "DELIVERED"));
    TransportRequest r = t.request();
    events.publish(EventTypes.TRANSPORT_DELIVERED, "flowpilot", Severity.SUCCESS, r.orderId(),
        new Payloads.TransportDelivered(transportId, r.kind(), r.orderId(), r.materialId(), r.quantity(), t.to()));
  }

  public FakeTransport transport(String transportId) {
    FakeTransport t = byId.get(transportId);
    if (t == null) throw DomainException.notFound("Transport", transportId);
    return t;
  }

  /** In request order. */
  public List<FakeTransport> transports() {
    return List.copyOf(byId.values());
  }

  /** Neither delivered nor cancelled, in request order. */
  public List<FakeTransport> open() {
    List<FakeTransport> out = new ArrayList<>();
    for (FakeTransport t : byId.values()) if (t.status().equals("OPEN") || t.status().equals("PICKED_UP")) out.add(t);
    return out;
  }
}

package io.nexus.flowpilot.transport;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.material.MaterialServiceImpl;
import io.nexus.flowpilot.store.Reservation;
import io.nexus.flowpilot.store.StockStore;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.flowpilot.store.TransportStore;
import java.util.Optional;

/**
 * Transport orders between business places. Every method runs in a unit of its own. The fleet picks up
 * changes (new, cancelled, redirected orders) in its next step.
 */
public final class TransportServiceImpl implements TransportService {
  private final TransportStore transports;
  private final StockStore stock;
  private final IdSource ids;
  private final EventPublisher events;
  private final UnitOfWork work;
  private final SimTime time;
  private final PlaceResolver places = new PlaceResolver();

  public TransportServiceImpl(TransportStore transports, StockStore stock, IdSource ids, EventPublisher events,
                              UnitOfWork work, SimTime time) {
    this.transports = transports;
    this.stock = stock;
    this.ids = ids;
    this.events = events;
    this.work = work;
    this.time = time;
  }

  public PlaceResolver places() {
    return places;
  }

  @Override
  public String requestTransport(TransportRequest r) {
    if (r == null || r.requestId() == null || r.requestId().isBlank()) {
      throw DomainException.invalid("Transport braucht eine requestId");
    }
    String[] id = new String[1];
    work.inNewTransaction(() -> {
      Optional<TransportOrder> existing = transports.byRequest(r.requestId());
      if (existing.isPresent()) {           // idempotent; checked first, a SUPPLY reservation may be consumed by now
        id[0] = existing.get().id();
        return;
      }
      validate(r);
      String fromNode = places.node(r.from());
      String toNode = places.node(r.to());
      var t = new TransportOrder(ids.next("TO"), r.requestId(), r.kind(), r.orderId(), r.materialId(), r.quantity(),
          r.from(), fromNode, r.to(), toNode, r.priority() == null ? Priority.NORMAL : r.priority(),
          TransportStatus.CREATED, null, r.reservationId(), time.nowMs(), 0);
      transports.insert(t);
      events.publish(EventTypes.TRANSPORT_CREATED, MaterialServiceImpl.SOURCE, Severity.INFO, r.orderId(),
          new Payloads.TransportCreated(t.id(), t.kind(), t.orderId(), t.materialId(), t.quantity(), t.from(), t.to()));
      id[0] = t.id();
    });
    return id[0];
  }

  private void validate(TransportRequest r) {
    if (r.kind() == null || r.from() == null || r.to() == null) {
      throw DomainException.invalid("Transport braucht Art, Quelle und Ziel");
    }
    if (r.quantity() <= 0) throw DomainException.invalid("Menge muss positiv sein");
    if (r.kind() != TransportKind.WIP && (r.materialId() == null || r.materialId().isBlank())) {
      throw DomainException.invalid("Versorgung und Einlagerung brauchen ein Material");
    }
    if (r.kind() == TransportKind.SUPPLY) {
      Reservation res = r.reservationId() == null ? null : stock.reservation(r.reservationId()).orElse(null);
      boolean matches = res != null && res.status() == Reservation.Status.ACTIVE
          && res.orderId().equals(r.orderId()) && res.materialId().equals(r.materialId())
          && res.quantity() == r.quantity() && r.from().equals(Place.storage(res.locationId()));
      if (!matches) {
        throw DomainException.invalid("Versorgung braucht eine aktive Reservierung desselben Auftrags, Materials, "
            + "Lagerplatzes und derselben Menge");
      }
    }
    if (r.kind() == TransportKind.PUTAWAY) checkPutawayTarget(r.to());
  }

  private void checkPutawayTarget(Place to) {
    if (to.kind() != Place.Kind.STORAGE || !places.location(to.id()).zone().equals("FINISHED")) {
      throw DomainException.invalid("Einlagerung geht nur in das Fertiglager");
    }
  }

  @Override
  public void cancel(String transportId) {
    work.inNewTransaction(() -> {
      TransportOrder t = find(transportId);
      TransportStatus.RULES.move(t.id(), t.status(), TransportStatus.CANCELLED);
      transports.update(t.withStatus(TransportStatus.CANCELLED, t.agvId()));
      events.publish(EventTypes.TRANSPORT_STATE, MaterialServiceImpl.SOURCE, Severity.INFO, t.orderId(),
          new Payloads.TransportStateChanged(t.id(), t.status(), TransportStatus.CANCELLED, t.agvId()));
    });
  }

  @Override
  public void redirect(String transportId, Place newTo) {
    work.inNewTransaction(() -> {
      TransportOrder t = find(transportId);
      if (t.status() == TransportStatus.UNLOADING || t.status().terminal()) {
        throw DomainException.conflict("Transport " + t.id() + " kann im Zustand " + t.status()
            + " nicht mehr umgeleitet werden");
      }
      if (t.kind() == TransportKind.PUTAWAY) checkPutawayTarget(newTo);
      transports.update(t.withTarget(newTo, places.node(newTo)));
    });
  }

  private TransportOrder find(String id) {
    return transports.byId(id).orElseThrow(() -> DomainException.notFound("Transportauftrag", id));
  }
}
